-- Server-authoritative wipe generation: a stale device must not be able to reverse an intentional
-- cloud wipe.
--
-- The defect this closes
-- ---------------------
-- `delete_all_user_cloud_data()` hard-deletes notes and tombstones. Nothing durable recorded that
-- the deletion was *intentional*, so a device that was offline across the wipe could not tell the
-- two apart, and the server could not tell either. Two consequences followed:
--
--   * `apply_note_change` on a note the server no longer has answers `conflict / note_not_found`,
--     which every client reads as "learn the revision and retry" — the retry inserts the note.
--   * A full snapshot of an empty cloud is indistinguishable from a truncated one, so each client
--     refuses it defensively (`SuspectEmptyCloudException`, `unexplainedMissingCloudIds`) and the
--     account's device is left in a permanently failing sync state rather than being told the
--     wipe happened.
--
-- The protocol
-- ------------
-- One durable generation per account, `account_sync_state.wipe_generation`. It starts at 0 and
-- `delete_all_user_cloud_data()` increments it in the same transaction as the deletes, returning
-- the new value.
--
-- Every authenticated client learns the generation from `fetch_full_snapshot()`, `pull_changes()`
-- or `get_sync_generation()` and presents it on each note mutation as `p_wipe_generation`.
--
-- Enforcement is a row-level trigger on `notes` and `note_tombstones`, not a check inside each
-- RPC. That is deliberate: it is the one place every write path already goes through, so a new
-- RPC, a future endpoint, or a direct PostgREST table write cannot bypass it. The mutation RPCs
-- copy the presented generation into the transaction-local `notelikeus.wipe_generation` setting
-- (exactly how `begin_sync_mutation` already drives `notelikeus.sync_mutation`), and the trigger
-- compares that against the account's durable value.
--
--   generation 0 (never wiped)  -> every write is allowed, with or without a generation. Accounts
--                                  that have never been wiped are untouched by this migration, and
--                                  a client older than this migration keeps working normally.
--   generation > 0 (wiped once) -> a write that presents no generation is refused
--                                  (SQLSTATE NL001), and one that presents a stale generation is
--                                  refused (SQLSTATE NL002).
--
-- The invariant: after a wipe, no note or tombstone can be created, updated or deleted by a writer
-- that cannot prove it knows the wipe happened. A stale device therefore cannot silently repopulate
-- the cloud; it must first learn the generation, and deciding whether to re-upload the notes it
-- still holds is an explicit user action on that device (the clients keep local notes and prompt).
--
-- Compatibility, rollout, recovery
-- -------------------------------
-- * Old clients (Android/Windows builds and web builds before this change) omit
--   `p_wipe_generation`. They keep working on accounts with generation 0 — which is every account
--   until its owner wipes. On a wiped account they get NL001 and their pushes fail visibly; that
--   is the intended outcome, and it is why this migration must ship *before* new clients are
--   expected, not after. Nothing needs to be blocked at the edge: the failure is scoped to
--   accounts whose owner asked for a wipe.
-- * Rollout order: apply this migration, then deploy clients that send the generation. The
--   migrations are additive (no column, table or function is dropped, and no existing signature
--   changes), so an older database plus a newer client is the only unsupported combination, and
--   the newer client degrades to today's behaviour there because the extra argument matches no
--   deployed overload.
-- * Recovery: the generation never decreases, and it is a counter, not a timestamp, so a database
--   restore from a backup taken before the wipe restores the old generation *and* the old notes —
--   self-consistent, and the next wipe increments from there. Re-running a wipe is idempotent for
--   the client and increments the generation again, which only refuses writes that are already
--   refused.
-- * `account_sync_state` rows are created by the wipe itself; an account with no row reads as
--   generation 0 everywhere.

CREATE TABLE IF NOT EXISTS public.account_sync_state (
    owner_id UUID PRIMARY KEY REFERENCES auth.users (id) ON DELETE CASCADE,
    wipe_generation BIGINT NOT NULL DEFAULT 0 CHECK (wipe_generation >= 0),
    wiped_at TIMESTAMPTZ
);

COMMENT ON TABLE public.account_sync_state IS
    'Per-account durable sync bookkeeping. wipe_generation increments on every intentional '
    'delete_all_user_cloud_data() and is what tells a device that was offline across a wipe that '
    'the cloud is not merely empty. Never deleted by the wipe itself.';

COMMENT ON COLUMN public.account_sync_state.wipe_generation IS
    '0 = the account has never been wiped. > 0 = every note mutation must present this value.';

ALTER TABLE public.account_sync_state ENABLE ROW LEVEL SECURITY;

CREATE POLICY account_sync_state_select_own ON public.account_sync_state
    FOR SELECT TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY account_sync_state_insert_own ON public.account_sync_state
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY account_sync_state_update_own ON public.account_sync_state
    FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

-- Same fence the other tables carry: the generation is written by the wipe RPC (which opens the
-- sync-mutation guard) and by nothing else. Without it, a direct PostgREST PATCH could lower an
-- account's own generation and re-open the resurrection window this migration closes.
CREATE TRIGGER account_sync_state_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.account_sync_state
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_mutation_guard();

/*
 * Refuses a note or tombstone write that cannot prove it knows about an intentional wipe.
 *
 * Runs as the invoker (`account_sync_state` is readable under its own RLS policy), and reads the
 * caller's generation rather than anything the caller supplied, so the presented value can only
 * ever be *stale* or *current* — never authoritative.
 */
CREATE OR REPLACE FUNCTION public.account_wipe_generation_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_owner UUID := CASE WHEN TG_OP = 'DELETE' THEN OLD.owner_id ELSE NEW.owner_id END;
    v_current BIGINT;
    v_presented TEXT := current_setting('notelikeus.wipe_generation', true);
BEGIN
    SELECT s.wipe_generation INTO v_current
    FROM public.account_sync_state s
    WHERE s.owner_id = v_owner;

    -- Never wiped (or no row yet): nothing to prove. This is the branch every existing account
    -- and every existing client takes.
    IF COALESCE(v_current, 0) = 0 THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;

    -- A client older than this migration cannot present a generation at all. Refusing is the
    -- point of the protocol; allowing it is exactly the silent resurrection it exists to stop.
    IF v_presented IS NULL OR v_presented !~ '^[0-9]+$' THEN
        RAISE EXCEPTION
            'account_wipe_generation_required: this account''s cloud data was deleted from another '
            'device. Update Notelikeus on this device, then reopen it to re-sync.'
            USING ERRCODE = 'NL001';
    END IF;

    IF v_presented::BIGINT <> v_current THEN
        RAISE EXCEPTION
            'account_wipe_generation_stale: this device last synced before this account''s cloud '
            'data was deleted from another device. Reopen Notelikeus to re-sync before editing.'
            USING ERRCODE = 'NL002';
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER notes_account_wipe_generation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.notes
    FOR EACH ROW
    EXECUTE FUNCTION public.account_wipe_generation_guard();

CREATE TRIGGER note_tombstones_account_wipe_generation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.note_tombstones
    FOR EACH ROW
    EXECUTE FUNCTION public.account_wipe_generation_guard();

/*
 * Copies the generation a client presented into the transaction, so the trigger above can read it.
 *
 * Deliberately not callable from PostgREST to any useful end: it only sets a transaction-local
 * value, and each REST request is its own transaction, so a caller outside a mutation RPC cannot
 * pair it with a table write. It is revoked from `anon` and left executable by `authenticated`
 * for the same reason `begin_sync_mutation` is — the mutation RPCs run `SECURITY INVOKER` and
 * therefore need EXECUTE themselves.
 */
CREATE OR REPLACE FUNCTION public.present_wipe_generation(p_wipe_generation BIGINT)
RETURNS void
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    PERFORM set_config(
        'notelikeus.wipe_generation',
        CASE WHEN p_wipe_generation IS NULL THEN '' ELSE p_wipe_generation::text END,
        true
    );
END;
$$;

/*
 * The caller's current wipe generation. Cheap enough for a client to call before a push, and the
 * way a client that was refused (NL001/NL002) learns what to present next.
 */
CREATE OR REPLACE FUNCTION public.get_sync_generation()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    RETURN jsonb_build_object(
        'wipe_generation',
        COALESCE((
            SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = v_owner
        ), 0)
    );
END;
$$;

-- Read paths carry the generation so a normal sync learns it without an extra round trip.
CREATE OR REPLACE FUNCTION public.fetch_full_snapshot()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    SELECT jsonb_build_object(
        'notes',
        COALESCE(
            (
                SELECT jsonb_agg(public.note_row_to_json(n.*) ORDER BY n.position, n.local_id)
                FROM public.notes n
                WHERE n.owner_id = auth.uid()
            ),
            '[]'::jsonb
        ),
        'tombstones',
        COALESCE(
            (
                SELECT jsonb_agg(public.tombstone_row_to_json(t.*) ORDER BY t.revision)
                FROM public.note_tombstones t
                WHERE t.owner_id = auth.uid()
            ),
            '[]'::jsonb
        ),
        'note_count',
        (SELECT count(*)::integer FROM public.notes n WHERE n.owner_id = auth.uid()),
        'wipe_generation',
        COALESCE((
            SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = auth.uid()
        ), 0)
    );
$$;

CREATE OR REPLACE FUNCTION public.pull_changes(
    p_after_revision BIGINT DEFAULT 0,
    p_limit INTEGER DEFAULT 100
)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    WITH combined AS (
        SELECT revision, public.note_row_to_json(n.*) AS change
        FROM public.notes n
        WHERE n.owner_id = auth.uid() AND n.revision > COALESCE(p_after_revision, 0)
        UNION ALL
        SELECT revision, public.tombstone_row_to_json(t.*) AS change
        FROM public.note_tombstones t
        WHERE t.owner_id = auth.uid() AND t.revision > COALESCE(p_after_revision, 0)
    ),
    limited AS (
        SELECT change, revision
        FROM combined
        ORDER BY revision ASC
        LIMIT GREATEST(p_limit, 1)
    ),
    totals AS (
        SELECT count(*) AS total FROM combined
    )
    SELECT jsonb_build_object(
        'changes',
        COALESCE((SELECT jsonb_agg(change ORDER BY revision ASC) FROM limited), '[]'::jsonb),
        'has_more',
        (SELECT total > p_limit FROM totals),
        'wipe_generation',
        COALESCE((
            SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = auth.uid()
        ), 0)
    );
$$;

/*
 * The wipe itself: unchanged except that it now increments and publishes the generation.
 *
 * The increment happens before any delete, in this transaction, so the generation a client is
 * told is never lower than the one the deletes ran under. The transaction then presents the new
 * generation to its own guard, because the wipe is the one writer that is allowed to delete a
 * wiped account's rows.
 */
CREATE OR REPLACE FUNCTION public.delete_all_user_cloud_data()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_notes INTEGER := 0;
    v_tombstones INTEGER := 0;
    v_attachments INTEGER := 0;
    v_generation BIGINT := 0;
    v_object_keys TEXT[] := ARRAY[]::text[];
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    INSERT INTO public.account_sync_state (owner_id, wipe_generation, wiped_at)
    VALUES (v_owner, 1, timezone('utc', now()))
    ON CONFLICT (owner_id) DO UPDATE
        SET wipe_generation = public.account_sync_state.wipe_generation + 1,
            wiped_at = timezone('utc', now())
    RETURNING wipe_generation INTO v_generation;

    PERFORM public.present_wipe_generation(v_generation);

    -- Every object the user owns, whether or not it was already soft-deleted. The previous
    -- `deleted_at IS NULL` filter is what stranded already-deleted attachments in R2.
    SELECT COALESCE(array_agg(object_key), ARRAY[]::text[])
    INTO v_object_keys
    FROM public.note_attachments
    WHERE owner_id = v_owner;

    -- Marked, not destroyed: the Worker still has to authorize each DELETE against this row,
    -- and the sweeper still has to be able to find it if the client never comes back.
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        purge_requested_at = COALESCE(purge_requested_at, timezone('utc', now()))
    WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_attachments = ROW_COUNT;

    DELETE FROM public.notes WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    DELETE FROM public.note_tombstones WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_tombstones = ROW_COUNT;

    DELETE FROM public.sync_meta WHERE owner_id = v_owner;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'notes_deleted', v_notes,
        'tombstones_deleted', v_tombstones,
        'attachments_deleted', v_attachments,
        'attachment_object_keys', to_jsonb(v_object_keys),
        'wipe_generation', v_generation
    );
END;
$$;

-- The pre-attachment arity, redefined for one character: `NULL` becomes `NULL::jsonb`.
--
-- It is a SQL function that delegates to the 15-argument arity by passing `NULL` for
-- `p_attachment_ids`, and an untyped `NULL` now matches *both* 15-argument candidates — the
-- attachment one (JSONB) and the generation one (BIGINT) below — so PostgreSQL refuses the inner
-- call as ambiguous and every legacy `apply_note_change` would fail. Behaviour is otherwise
-- identical, and the explicit cast is what the arity always meant.
CREATE OR REPLACE FUNCTION public.apply_note_change(
    p_note_id TEXT,
    p_local_id BIGINT,
    p_base_revision BIGINT,
    p_title TEXT,
    p_content TEXT,
    p_client_timestamp BIGINT,
    p_color INTEGER,
    p_is_pinned BOOLEAN,
    p_is_archived BOOLEAN,
    p_is_trashed BOOLEAN,
    p_position INTEGER,
    p_reminder_timestamp BIGINT,
    p_labels JSONB,
    p_checklist JSONB
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = public
AS $$
    -- R19.1: the pre-change arity stays for clients that predate the attachment protocol. They name no
    -- attachment set, and their uploads were committed at upload time, so nothing is promoted here.
    SELECT public.apply_note_change(
        p_note_id, p_local_id, p_base_revision, p_title, p_content, p_client_timestamp, p_color,
        p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp, p_labels,
        p_checklist, NULL::jsonb
    );
$$;

-- Mutation wrappers that present the generation.
--
-- Each one is a thin delegation to the overload that already exists, so there is exactly one
-- implementation of every mutation and no behaviour can drift between the arities. Positional
-- notation is safe here and named notation would not be: none of the arities declares a DEFAULT,
-- so a call's argument count selects exactly one candidate, and the two 15-argument overloads
-- differ in the type of their last argument (JSONB vs BIGINT).

CREATE OR REPLACE FUNCTION public.apply_note_change(
    p_note_id TEXT,
    p_local_id BIGINT,
    p_base_revision BIGINT,
    p_title TEXT,
    p_content TEXT,
    p_client_timestamp BIGINT,
    p_color INTEGER,
    p_is_pinned BOOLEAN,
    p_is_archived BOOLEAN,
    p_is_trashed BOOLEAN,
    p_position INTEGER,
    p_reminder_timestamp BIGINT,
    p_labels JSONB,
    p_checklist JSONB,
    p_wipe_generation BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    PERFORM public.present_wipe_generation(p_wipe_generation);
    RETURN public.apply_note_change(
        p_note_id, p_local_id, p_base_revision, p_title, p_content, p_client_timestamp,
        p_color, p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp,
        p_labels, p_checklist
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.apply_note_change(
    p_note_id TEXT,
    p_local_id BIGINT,
    p_base_revision BIGINT,
    p_title TEXT,
    p_content TEXT,
    p_client_timestamp BIGINT,
    p_color INTEGER,
    p_is_pinned BOOLEAN,
    p_is_archived BOOLEAN,
    p_is_trashed BOOLEAN,
    p_position INTEGER,
    p_reminder_timestamp BIGINT,
    p_labels JSONB,
    p_checklist JSONB,
    p_attachment_ids JSONB,
    p_wipe_generation BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    PERFORM public.present_wipe_generation(p_wipe_generation);
    RETURN public.apply_note_change(
        p_note_id, p_local_id, p_base_revision, p_title, p_content, p_client_timestamp,
        p_color, p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp,
        p_labels, p_checklist, p_attachment_ids
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.apply_note_delete(
    p_note_id TEXT,
    p_base_revision BIGINT,
    p_wipe_generation BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    PERFORM public.present_wipe_generation(p_wipe_generation);
    RETURN public.apply_note_delete(p_note_id, p_base_revision);
END;
$$;

CREATE OR REPLACE FUNCTION public.restore_note(
    p_note_id TEXT,
    p_local_id BIGINT,
    p_base_revision BIGINT,
    p_title TEXT,
    p_content TEXT,
    p_client_timestamp BIGINT,
    p_color INTEGER,
    p_is_pinned BOOLEAN,
    p_is_archived BOOLEAN,
    p_is_trashed BOOLEAN,
    p_position INTEGER,
    p_reminder_timestamp BIGINT,
    p_labels JSONB,
    p_checklist JSONB,
    p_wipe_generation BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    PERFORM public.present_wipe_generation(p_wipe_generation);
    RETURN public.restore_note(
        p_note_id, p_local_id, p_base_revision, p_title, p_content, p_client_timestamp,
        p_color, p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp,
        p_labels, p_checklist
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_sync_generation() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.present_wipe_generation(BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.account_wipe_generation_guard() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, BIGINT
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, JSONB, BIGINT
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_delete(TEXT, BIGINT, BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.restore_note(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, BIGINT
) FROM PUBLIC, anon;

-- `authenticated` needs the helpers because the RPCs above run SECURITY INVOKER.
GRANT EXECUTE ON FUNCTION public.get_sync_generation() TO authenticated;
GRANT EXECUTE ON FUNCTION public.present_wipe_generation(BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.account_wipe_generation_guard() TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, BIGINT
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, JSONB, BIGINT
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_delete(TEXT, BIGINT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.restore_note(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, BIGINT
) TO authenticated;
