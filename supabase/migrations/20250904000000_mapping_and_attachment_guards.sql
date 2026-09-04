-- Close two confirmed direct-PostgREST bypasses of the RPC invariants.
--
-- `notes` and `note_tombstones` have carried `sync_mutation_guard` since
-- 20250902010000, so their revision/owner invariants cannot be forged by a direct table write.
-- `firebase_uid_mappings` and `note_attachments` did not, even though both hold invariants that
-- exist only inside their RPCs:
--
--   * `link_firebase_uid` refuses a uid already claimed by another account — but an authenticated
--     client could INSERT the mapping row directly and skip that check entirely.
--   * `register_note_attachment` requires `object_key = owners/{auth.uid()}/notes/{note}/{att}` —
--     but a direct INSERT could store any `object_key`. Combined with the global
--     `note_attachments_object_key_unique`, that lets one account squat another account's future
--     object key and permanently break their attachment registration.
--
-- Neither bypass leaks another user's data (RLS still scopes every row to `owner_id = auth.uid()`,
-- and the R2 Worker derives the object key from the bearer token rather than from this table), but
-- both are durable denial-of-migration and denial-of-upload against a specific victim.
--
-- Every client write already goes through these RPCs (web `supabaseAttachmentMetadata.ts`,
-- Kotlin `SupabaseAttachmentMetadata`/`FirebaseSupabaseAccountLinker`), so guarding the tables
-- does not remove a path anything uses.

-- The guard is opened by `set_config(..., is_local => true)`, which lasts for the whole
-- transaction — not just the statement that needed it. Nothing reachable through PostgREST can
-- exploit that today (one RPC call is one transaction, and no exposed function runs attacker SQL
-- afterwards), but "the guard is open for everything that follows" is not what the guard is meant
-- to say, and it silently breaks any caller that runs an RPC and a direct-write assertion in one
-- transaction — pgTAP test files, for one, since each file is a single transaction.
-- Bracketing every mutating RPC makes the window the statements that actually need it.

CREATE OR REPLACE FUNCTION public.begin_sync_mutation()
RETURNS void
LANGUAGE sql
SET search_path = public
AS $$ SELECT set_config('notelikeus.sync_mutation', '1', true); $$;

CREATE OR REPLACE FUNCTION public.end_sync_mutation()
RETURNS void
LANGUAGE sql
SET search_path = public
AS $$ SELECT set_config('notelikeus.sync_mutation', '', true); $$;

CREATE TRIGGER firebase_uid_mappings_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.firebase_uid_mappings
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_mutation_guard();

CREATE TRIGGER note_attachments_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.note_attachments
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_mutation_guard();

-- The RPCs that legitimately write these tables now have to open the guard, exactly as the note
-- sync RPCs do. `delete_all_user_cloud_data` already calls set_config and needs no change.

CREATE OR REPLACE FUNCTION public.link_firebase_uid(p_firebase_uid TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_uid TEXT := trim(p_firebase_uid);
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF v_uid IS NULL OR char_length(v_uid) = 0 THEN
        RAISE EXCEPTION 'firebase_uid required' USING ERRCODE = '22023';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.firebase_uid_mappings
        WHERE firebase_uid = v_uid AND owner_id <> v_owner
    ) THEN
        RAISE EXCEPTION 'firebase_uid already linked to another account' USING ERRCODE = '23505';
    END IF;

    INSERT INTO public.firebase_uid_mappings (firebase_uid, owner_id)
    VALUES (v_uid, v_owner)
    ON CONFLICT (owner_id) DO UPDATE
        SET firebase_uid = EXCLUDED.firebase_uid,
            linked_at = timezone('utc', now())
    WHERE public.firebase_uid_mappings.owner_id = v_owner;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'firebase_uid', v_uid,
        'owner_id', v_owner::text
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.register_note_attachment(
    p_attachment_id TEXT,
    p_note_id TEXT,
    p_object_key TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT,
    p_attachment_type TEXT DEFAULT 'image'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_key TEXT := trim(p_object_key);
    v_expected TEXT;
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF trim(p_attachment_id) = '' OR trim(p_note_id) = '' OR v_key = '' THEN
        RAISE EXCEPTION 'attachment_id, note_id, and object_key required' USING ERRCODE = '22023';
    END IF;

    v_expected := public.expected_attachment_object_key(v_owner, p_note_id, p_attachment_id);
    IF v_key <> v_expected THEN
        RAISE EXCEPTION 'object_key does not match owner namespace' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.note_attachments (
        attachment_id,
        owner_id,
        note_id,
        object_key,
        mime_type,
        size_bytes,
        attachment_type,
        deleted_at
    )
    VALUES (
        trim(p_attachment_id),
        v_owner,
        trim(p_note_id),
        v_key,
        coalesce(nullif(trim(p_mime_type), ''), 'application/octet-stream'),
        greatest(coalesce(p_size_bytes, 0), 0),
        coalesce(nullif(trim(p_attachment_type), ''), 'image'),
        NULL
    )
    ON CONFLICT (owner_id, attachment_id) DO UPDATE
        SET note_id = EXCLUDED.note_id,
            object_key = EXCLUDED.object_key,
            mime_type = EXCLUDED.mime_type,
            size_bytes = EXCLUDED.size_bytes,
            attachment_type = EXCLUDED.attachment_type,
            deleted_at = NULL;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'attachment_id', trim(p_attachment_id),
        'note_id', trim(p_note_id),
        'object_key', v_key
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.delete_note_attachment(
    p_attachment_id TEXT,
    p_note_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    UPDATE public.note_attachments
    SET deleted_at = timezone('utc', now())
    WHERE owner_id = v_owner
      AND attachment_id = trim(p_attachment_id)
      AND note_id = trim(p_note_id)
      AND deleted_at IS NULL;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'deleted', found,
        'attachment_id', trim(p_attachment_id)
    );
END;
$$;

-- Same bracketing for the note sync RPCs and the account wipe. The bodies are unchanged from
-- 20250902010000 / 20250902060000 apart from opening the guard through `begin_sync_mutation()`
-- and closing it before each RETURN.

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
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
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
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.apply_note_delete(
    p_note_id TEXT,
    p_base_revision BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_existing public.notes%ROWTYPE;
    v_new_revision BIGINT;
    v_deleted_at TIMESTAMPTZ := timezone('utc', now());
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    IF NOT FOUND THEN
        IF EXISTS (
            SELECT 1 FROM public.note_tombstones
            WHERE owner_id = v_owner AND note_id = p_note_id
        ) THEN
            PERFORM public.end_sync_mutation();
            RETURN jsonb_build_object('status', 'applied', 'idempotent', true);
        END IF;
        PERFORM public.end_sync_mutation();
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_not_found');
    END IF;

    IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
        PERFORM public.end_sync_mutation();
        RETURN jsonb_build_object(
            'status', 'conflict',
            'current', public.note_row_to_json(v_existing)
        );
    END IF;

    DELETE FROM public.notes WHERE owner_id = v_owner AND note_id = p_note_id;

    v_new_revision := nextval('public.sync_revision_seq');
    INSERT INTO public.note_tombstones (note_id, owner_id, revision, deleted_at)
    VALUES (p_note_id, v_owner, v_new_revision, v_deleted_at)
    ON CONFLICT (owner_id, note_id) DO UPDATE
        SET revision = EXCLUDED.revision,
            deleted_at = EXCLUDED.deleted_at;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'deleted_at', (extract(epoch FROM v_deleted_at) * 1000)::bigint
    );
END;
$$;

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
    v_object_keys TEXT[] := ARRAY[]::text[];
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    SELECT COALESCE(array_agg(object_key), ARRAY[]::text[])
    INTO v_object_keys
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND deleted_at IS NULL;

    DELETE FROM public.note_attachments WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_attachments = ROW_COUNT;

    DELETE FROM public.notes WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    DELETE FROM public.note_tombstones WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_tombstones = ROW_COUNT;

    DELETE FROM public.sync_meta WHERE owner_id = v_owner;
    DELETE FROM public.firebase_uid_mappings WHERE owner_id = v_owner;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'notes_deleted', v_notes,
        'tombstones_deleted', v_tombstones,
        'attachments_deleted', v_attachments,
        'attachment_object_keys', to_jsonb(v_object_keys)
    );
END;
$$;

-- The RPCs run SECURITY INVOKER, so `authenticated` still needs nextval on the revision sequence.
-- `anon` never reaches an RPC that mutates (every one of them raises 28000 first), so it has no
-- reason to hold USAGE/UPDATE on the sequence that assigns sync revisions.
REVOKE ALL ON SEQUENCE public.sync_revision_seq FROM anon;

-- Grant hygiene. Two separate default grants make `anon` executable here, and revoking either
-- one alone leaves the other in place:
--   * PUBLIC holds Postgres' own default EXECUTE on every new function;
--   * Supabase's `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON FUNCTIONS TO anon, authenticated,
--     service_role` adds an explicit per-role grant on top.
-- The `REVOKE ALL ... FROM PUBLIC` in 20250902000000 therefore never actually removed anon's
-- EXECUTE on the sync RPCs (verified against a Supabase-equivalent database). Revoking from both
-- is what closes it. This changes no behaviour — every one of these functions already raises
-- 28000, or filters on a NULL `auth.uid()`, for an anonymous caller — it makes the grant table
-- state the boundary instead of relying solely on the check inside each function body.
REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_delete(TEXT, BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.pull_changes(BIGINT, INTEGER) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fetch_full_snapshot() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.delete_all_user_cloud_data() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.link_firebase_uid(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.get_linked_firebase_uid() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.register_note_attachment(
    TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.delete_note_attachment(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.list_note_attachments(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.list_user_attachments() FROM PUBLIC, anon;
-- Internal helpers: only ever called from inside the RPCs above (which run SECURITY INVOKER, so
-- `authenticated` still needs EXECUTE). Nothing should reach them from the Data API directly.
REVOKE ALL ON FUNCTION public.note_row_to_json(public.notes) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.tombstone_row_to_json(public.note_tombstones) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.expected_attachment_object_key(UUID, TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.sync_mutation_guard() FROM PUBLIC, anon;
-- `authenticated` needs these because the RPCs are SECURITY INVOKER; `anon` never reaches one.
REVOKE ALL ON FUNCTION public.begin_sync_mutation() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.end_sync_mutation() FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_delete(TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.pull_changes(BIGINT, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fetch_full_snapshot() TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_all_user_cloud_data() TO authenticated;
GRANT EXECUTE ON FUNCTION public.link_firebase_uid(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_linked_firebase_uid() TO authenticated;
GRANT EXECUTE ON FUNCTION public.register_note_attachment(
    TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_note_attachment(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.list_note_attachments(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.list_user_attachments() TO authenticated;
GRANT EXECUTE ON FUNCTION public.note_row_to_json(public.notes) TO authenticated;
GRANT EXECUTE ON FUNCTION public.tombstone_row_to_json(public.note_tombstones) TO authenticated;
GRANT EXECUTE ON FUNCTION public.expected_attachment_object_key(UUID, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.begin_sync_mutation() TO authenticated;
GRANT EXECUTE ON FUNCTION public.end_sync_mutation() TO authenticated;
