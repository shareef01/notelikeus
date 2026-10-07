-- R19.1: attachment metadata is provisional until the note that references it commits.
--
-- An upload is written, and its metadata row created, before the note that references the attachment has
-- been pushed. If that note continuation then goes stale -- a dataset transition landed behind the
-- upload's drain point, which F-10 explicitly allows -- the row used to keep exactly the shape a
-- committed row has: `(owner, note_id)` and nothing else to say that no note ever referenced it. A
-- replacement dataset that reuses the same note id (the normal case after a same-UID isolation or a
-- reinstall, because local ids restart) then hydrated that object onto an unrelated note.
--
-- The fix is a commitment marker, not a dataset namespace. The account's cloud attachment set is one
-- logical dataset -- devices, reinstalls and same-UID isolations do not create a second one -- so a
-- legitimately committed attachment must stay visible everywhere. What must not be visible is a row that
-- no committed note ever referenced. `committed_at` records exactly that, and the note commit sets it, in
-- the same transaction, for exactly the attachment ids that note version names.
--
-- Compatibility: the pre-change arities of `apply_note_change` and `finalize_note_attachment_put` remain,
-- keep their exact signatures (no defaulted parameter was added to them -- that would make existing
-- callers ambiguous), and keep their behaviour: an upload that does not opt into the protocol commits its
-- row immediately, and its note commits promote nothing.

DO $$
DECLARE
    v_column_was_missing BOOLEAN := NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'note_attachments' AND column_name = 'committed_at'
    );
BEGIN
    IF v_column_was_missing THEN
        ALTER TABLE public.note_attachments ADD COLUMN committed_at TIMESTAMPTZ;

        -- Legacy policy, executed exactly once, when the marker is introduced: every row that predates it
        -- keeps precisely the visibility it has today. A migration-time operation on rows the server
        -- already holds -- it stamps no client state, adopts nothing, and cannot run again (a second run
        -- finds the column present and leaves every provisional row alone).
        PERFORM set_config('notelikeus.sync_mutation', '1', true);
        UPDATE public.note_attachments
        SET committed_at = created_at
        WHERE committed_at IS NULL;
    END IF;
END;
$$;

COMMENT ON COLUMN public.note_attachments.committed_at IS
    'When a successful note commit referenced this row. NULL while provisional: an upload that completed '
    'but whose note continuation never committed. Only committed rows hydrate.';

CREATE OR REPLACE FUNCTION public.list_user_attachments()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public'
AS $function$
    SELECT coalesce(
        jsonb_agg(
            jsonb_build_object(
                'attachment_id', a.attachment_id,
                'note_id', a.note_id,
                'object_key', a.object_key,
                'mime_type', a.mime_type,
                'size_bytes', a.size_bytes,
                'attachment_type', a.attachment_type,
                'created_at', extract(epoch from a.created_at) * 1000
            )
            ORDER BY a.created_at ASC
        ),
        '[]'::jsonb
    )
    FROM public.note_attachments a
    WHERE a.owner_id = auth.uid()
      AND a.deleted_at IS NULL
      -- R19.1: an upload whose note never committed is provisional and must not hydrate. Rows that
      -- predate the marker were stamped committed by this migration, so their visibility is unchanged.
      AND a.committed_at IS NOT NULL;
$function$;

CREATE OR REPLACE FUNCTION public.finalize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_object_key TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT,
    p_provisional BOOLEAN,
    p_attachment_type TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $function$

DECLARE
    v_owner UUID := auth.uid();
    v_key TEXT := trim(p_object_key);
    v_expected TEXT;
    v_attachment_id TEXT := trim(p_attachment_id);
    v_note_id TEXT := trim(p_note_id);
    v_mime TEXT := lower(split_part(trim(p_mime_type), ';', 1));
    v_type TEXT := coalesce(nullif(trim(p_attachment_type), ''), 'image');
    v_live_count INTEGER;
    v_user_bytes BIGINT;
    v_existing public.note_attachments%ROWTYPE;
BEGIN
    PERFORM public.begin_sync_mutation();


    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    -- Serialise quota accounting per owner. The count and the sum below are read before the
    -- insert, so two concurrent finalizations both saw the same pre-insert totals, both decided
    -- they fit, and together exceeded the limit. The lock is per owner and released at commit,
    -- so contention is bounded to one user's own concurrent uploads.
    PERFORM pg_advisory_xact_lock(hashtextextended('note_attachment_quota:' || v_owner::text, 0));
    IF NOT public.is_valid_attachment_path_id(v_attachment_id)
       OR NOT public.is_valid_attachment_path_id(v_note_id) THEN
        RAISE EXCEPTION 'invalid attachment or note id' USING ERRCODE = '22023';
    END IF;
    IF v_key = '' THEN
        RAISE EXCEPTION 'attachment_id, note_id, and object_key required' USING ERRCODE = '22023';
    END IF;
    IF v_type <> 'image' THEN
        RAISE EXCEPTION 'attachment_type not allowed' USING ERRCODE = '22023';
    END IF;
    IF NOT public.is_allowed_attachment_mime(v_mime) THEN
        RAISE EXCEPTION 'mime type not allowed' USING ERRCODE = '22023';
    END IF;
    IF p_size_bytes IS NULL OR p_size_bytes < 1 OR p_size_bytes > 10485760 THEN
        RAISE EXCEPTION 'size_bytes must be between 1 and 10485760' USING ERRCODE = '22023';
    END IF;

    PERFORM public.assert_live_owned_note(v_note_id);

    v_expected := public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id);
    IF v_key <> v_expected THEN
        RAISE EXCEPTION 'object_key does not match owner namespace' USING ERRCODE = '22023';
    END IF;

    -- The authoritative terminal-delete gate. It has to be here, holding the conflict row's own
    -- lock, because a DELETE can claim the attachment at any point after the Worker's preflight
    -- said yes -- including while its bytes are being uploaded. Locking the row this INSERT would
    -- conflict on serialises the two: either the claim is already visible here and this returns
    -- terminally_deleted, or this insert commits first and the claim waits and then wins.
    --
    -- Refused as a value rather than an exception on purpose. The Worker has usually written
    -- bytes by now, and it can only safely delete them again if the database says, without
    -- ambiguity, that this identity can never be live -- which an exception, a timeout and an
    -- unreachable database are indistinguishable from.
    SELECT * INTO v_existing
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND attachment_id = v_attachment_id
    FOR UPDATE;

    IF FOUND AND public.attachment_delete_is_terminal(v_existing) THEN
        -- This is the only non-exception exit after begin_sync_mutation, so it has to disarm the
        -- guard itself. Every other refusal here RAISEs and takes the transaction with it.
        PERFORM public.end_sync_mutation();
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'terminally_deleted',
            'object_key', v_expected
        );
    END IF;

    SELECT count(*) INTO v_live_count
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_live_count >= 20 THEN
        RAISE EXCEPTION 'too many attachments on note (max 20)' USING ERRCODE = '22023';
    END IF;

    SELECT coalesce(sum(size_bytes), 0) INTO v_user_bytes
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_user_bytes + p_size_bytes > 209715200 THEN
        RAISE EXCEPTION 'user attachment quota exceeded' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.note_attachments (
        attachment_id,
        owner_id,
        note_id,
        object_key,
        mime_type,
        size_bytes,
        attachment_type,
        deleted_at,
        committed_at
    )
    VALUES (
        v_attachment_id,
        v_owner,
        v_note_id,
        v_key,
        v_mime,
        p_size_bytes,
        v_type,
        NULL,
        -- A legacy upload commits the moment it is stored; a new-protocol upload stays provisional
        -- until the note that references it commits.
        CASE WHEN p_provisional THEN NULL ELSE timezone('utc', now()) END
    )
    ON CONFLICT (owner_id, attachment_id) DO UPDATE
        SET note_id = EXCLUDED.note_id,
            object_key = EXCLUDED.object_key,
            mime_type = EXCLUDED.mime_type,
            size_bytes = EXCLUDED.size_bytes,
            attachment_type = EXCLUDED.attachment_type,
            deleted_at = NULL,
            -- Committed is a one-way door for a row that is already visible.
            committed_at = coalesce(public.note_attachments.committed_at, EXCLUDED.committed_at);

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'attachment_id', v_attachment_id,
        'note_id', v_note_id,
        'object_key', v_key
    );
END;
$function$;

CREATE OR REPLACE FUNCTION public.finalize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_object_key TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT,
    p_attachment_type TEXT DEFAULT 'image'
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = public
AS $$
    -- R19.1: an upload that does not opt into the provisional protocol commits its row immediately,
    -- exactly as every upload did before this change, so an installed older client keeps working.
    SELECT public.finalize_note_attachment_put(
        p_note_id, p_attachment_id, p_object_key, p_mime_type, p_size_bytes, false, p_attachment_type
    );
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
    p_attachment_ids JSONB
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $function$

DECLARE
    v_owner UUID := auth.uid();
    v_existing public.notes%ROWTYPE;
    v_new_revision BIGINT;
    v_server_updated_at TIMESTAMPTZ := timezone('utc', now());
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    IF p_note_id IS DISTINCT FROM p_local_id::text THEN
        RAISE EXCEPTION 'note_id must match local_id text form' USING ERRCODE = '22023';
    END IF;

    PERFORM public.validate_note_payload(
        p_title, p_content, p_color, p_position, p_client_timestamp,
        p_reminder_timestamp, COALESCE(p_labels, '[]'::jsonb), COALESCE(p_checklist, '[]'::jsonb)
    );

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    IF FOUND THEN
        IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
            PERFORM public.end_sync_mutation();
            RETURN jsonb_build_object(
                'status', 'conflict',
                'current', public.note_row_to_json(v_existing)
            );
        END IF;

        v_new_revision := nextval('public.sync_revision_seq');
        UPDATE public.notes
        SET
            revision = v_new_revision,
            title = p_title,
            content = p_content,
            client_timestamp = p_client_timestamp,
            color = p_color,
            is_pinned = p_is_pinned,
            is_archived = p_is_archived,
            is_trashed = p_is_trashed,
            position = p_position,
            reminder_timestamp = p_reminder_timestamp,
            labels = COALESCE(p_labels, '[]'::jsonb),
            checklist = COALESCE(p_checklist, '[]'::jsonb),
            server_updated_at = v_server_updated_at
        WHERE owner_id = v_owner AND note_id = p_note_id
        RETURNING revision INTO v_new_revision;

        PERFORM public.end_sync_mutation();
    -- R19.1: the note version above has committed, in this same transaction, and these are the
    -- attachments it names. Exactly these rows become visible: a provisional row for the same note that
    -- this version does *not* name -- an orphan left by an earlier continuation that went stale -- stays
    -- provisional, and therefore stays out of hydration.
    --
    -- The promotion is inline rather than a helper the client could call: `v_owner` is `auth.uid()`, so
    -- only the committing account's own rows are eligible, RLS restricts the update to them, and there is
    -- no separate entry point that could be pointed at another account's rows.
    IF p_attachment_ids IS NOT NULL THEN
        -- `sync_mutation_guard` gates direct table writes on this transaction-local flag; the note
        -- write above released it, so it is re-asserted for this, the same transaction's promotion.
        PERFORM set_config('notelikeus.sync_mutation', '1', true);
        UPDATE public.note_attachments a
        SET committed_at = coalesce(a.committed_at, v_server_updated_at)
        WHERE a.owner_id = v_owner
          AND a.note_id = p_note_id
          AND a.deleted_at IS NULL
          AND a.committed_at IS NULL
          AND a.attachment_id IN (
              SELECT trim(value) FROM jsonb_array_elements_text(p_attachment_ids) AS value
          );
    END IF;

        RETURN jsonb_build_object(
            'status', 'applied',
            'revision', v_new_revision,
            'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
        );
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = p_note_id
    ) THEN
        PERFORM public.end_sync_mutation();
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_deleted');
    END IF;

    IF p_base_revision IS NOT NULL THEN
        PERFORM public.end_sync_mutation();
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_not_found');
    END IF;

    v_new_revision := nextval('public.sync_revision_seq');
    INSERT INTO public.notes (
        note_id,
        local_id,
        owner_id,
        revision,
        title,
        content,
        client_timestamp,
        color,
        is_pinned,
        is_archived,
        is_trashed,
        position,
        reminder_timestamp,
        labels,
        checklist,
        server_updated_at
    ) VALUES (
        p_note_id,
        p_local_id,
        v_owner,
        v_new_revision,
        p_title,
        p_content,
        p_client_timestamp,
        p_color,
        p_is_pinned,
        p_is_archived,
        p_is_trashed,
        p_position,
        p_reminder_timestamp,
        COALESCE(p_labels, '[]'::jsonb),
        COALESCE(p_checklist, '[]'::jsonb),
        v_server_updated_at
    );

    PERFORM public.end_sync_mutation();
    -- R19.1: the note version above has committed, in this same transaction, and these are the
    -- attachments it names. Exactly these rows become visible: a provisional row for the same note that
    -- this version does *not* name -- an orphan left by an earlier continuation that went stale -- stays
    -- provisional, and therefore stays out of hydration.
    --
    -- The promotion is inline rather than a helper the client could call: `v_owner` is `auth.uid()`, so
    -- only the committing account's own rows are eligible, RLS restricts the update to them, and there is
    -- no separate entry point that could be pointed at another account's rows.
    IF p_attachment_ids IS NOT NULL THEN
        -- `sync_mutation_guard` gates direct table writes on this transaction-local flag; the note
        -- write above released it, so it is re-asserted for this, the same transaction's promotion.
        PERFORM set_config('notelikeus.sync_mutation', '1', true);
        UPDATE public.note_attachments a
        SET committed_at = coalesce(a.committed_at, v_server_updated_at)
        WHERE a.owner_id = v_owner
          AND a.note_id = p_note_id
          AND a.deleted_at IS NULL
          AND a.committed_at IS NULL
          AND a.attachment_id IN (
              SELECT trim(value) FROM jsonb_array_elements_text(p_attachment_ids) AS value
          );
    END IF;

    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$function$;

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
        p_checklist, NULL
    );
$$;


REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, JSONB
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB, JSONB
) TO authenticated;

REVOKE ALL ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, BOOLEAN, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, BOOLEAN, TEXT) TO authenticated;
