-- Audit of 2026-11-02, AUD-008: the full snapshot can be read a page at a time.
--
-- `fetch_full_snapshot()` returns every note and tombstone of the account as one jsonb value. Note
-- text is now capped at 100 MiB per account (20261105000000), but one response of that size can still
-- exceed the statement timeout or a proxy's limit, and an account that hit it could not complete a
-- full sync on any client.
--
-- `fetch_snapshot_page(kind, after, limit)` returns one page of notes or of tombstones, ordered by
-- note id and continued from `after`. A notes page stops at 4 MiB of note text, or at `limit` rows,
-- whichever comes first, and always holds at least one row, so a single large note still makes
-- progress.
--
-- Pages are separate requests, so the account can change between them. Every page therefore also
-- reports the account's state: its newest revision, wipe generation, note count and tombstone count.
-- Every write that changes what a snapshot contains moves one of those: each revision-writing RPC
-- allocates a revision under the per-owner lock, a wipe moves the generation and the counts, and
-- clearing a tombstone moves the tombstone count. A client accepts the pages it assembled only when
-- every page reported the same state, and otherwise reads them again. That makes the assembled
-- snapshot what `fetch_full_snapshot()` would have returned at that state.
--
-- `fetch_full_snapshot()` is unchanged and stays for clients that predate this; the clients that ship
-- with this fall back to it when the database does not have the paged function yet.
--
-- Rollback: drop the function. Nothing else changes.

CREATE OR REPLACE FUNCTION public.fetch_snapshot_page(
    p_kind TEXT,
    p_after TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 500
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 500), 1000));
    v_byte_budget BIGINT := 4 * 1024 * 1024;
    v_items jsonb;
    v_last TEXT;
    v_has_more BOOLEAN;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    -- The same fence as fetch_full_snapshot: a deleted account is a definite answer, not an empty one.
    PERFORM notelikeus_internal.assert_account_not_deleted(v_owner);

    IF p_kind = 'notes' THEN
        WITH candidates AS (
            SELECT n AS note, notelikeus_internal.note_row_bytes(n) AS bytes
            FROM public.notes n
            WHERE n.owner_id = v_owner
              AND (p_after IS NULL OR n.note_id > p_after)
            ORDER BY n.note_id
            LIMIT v_limit
        ),
        running AS (
            SELECT note,
                   sum(bytes) OVER (ORDER BY (note).note_id) AS total,
                   row_number() OVER (ORDER BY (note).note_id) AS position_in_page
            FROM candidates
        ),
        page AS (
            SELECT note FROM running WHERE position_in_page = 1 OR total <= v_byte_budget
        )
        SELECT jsonb_agg(public.note_row_to_json(note) ORDER BY (note).note_id), max((note).note_id)
        INTO v_items, v_last
        FROM page;

        v_has_more := v_last IS NOT NULL AND EXISTS (
            SELECT 1 FROM public.notes n WHERE n.owner_id = v_owner AND n.note_id > v_last
        );
    ELSIF p_kind = 'tombstones' THEN
        WITH page AS (
            SELECT t FROM public.note_tombstones t
            WHERE t.owner_id = v_owner
              AND (p_after IS NULL OR t.note_id > p_after)
            ORDER BY t.note_id
            LIMIT v_limit
        )
        SELECT jsonb_agg(public.tombstone_row_to_json(t) ORDER BY (t).note_id), max((t).note_id)
        INTO v_items, v_last
        FROM page;

        v_has_more := v_last IS NOT NULL AND EXISTS (
            SELECT 1 FROM public.note_tombstones t WHERE t.owner_id = v_owner AND t.note_id > v_last
        );
    ELSE
        RAISE EXCEPTION 'kind must be notes or tombstones' USING ERRCODE = '22023';
    END IF;

    RETURN jsonb_build_object(
        'items', COALESCE(v_items, '[]'::jsonb),
        'next_after', CASE WHEN v_has_more THEN v_last END,
        'state', jsonb_build_object(
            'head_revision', GREATEST(
                COALESCE((SELECT max(n.revision) FROM public.notes n WHERE n.owner_id = v_owner), 0),
                COALESCE((SELECT max(t.revision) FROM public.note_tombstones t WHERE t.owner_id = v_owner), 0)
            ),
            'wipe_generation', COALESCE((
                SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = v_owner
            ), 0),
            'note_count', (SELECT count(*)::integer FROM public.notes n WHERE n.owner_id = v_owner),
            'tombstone_count',
                (SELECT count(*)::integer FROM public.note_tombstones t WHERE t.owner_id = v_owner)
        )
    );
END;
$$;

REVOKE ALL ON FUNCTION public.fetch_snapshot_page(TEXT, TEXT, INTEGER) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_snapshot_page(TEXT, TEXT, INTEGER) TO authenticated;
