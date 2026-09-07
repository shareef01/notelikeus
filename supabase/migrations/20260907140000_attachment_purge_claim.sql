-- Stop the orphan sweeper and a restore from both winning.
--
-- The sweeper listed eligible rows, deleted the R2 object, then asked the database to purge the
-- metadata. purge_orphaned_deleted_attachment re-checks whether the note came back and correctly
-- refuses when it has — but by then the bytes were already gone. A restore landing between the
-- listing and the R2 delete therefore produced live attachment metadata pointing at an object
-- that no longer exists, which is worse than either outcome on its own.
--
-- Deletion is now claimed before any byte is touched. The claim takes a row lock and re-checks
-- eligibility, so it serialises against restore: either the restore clears deleted_at first and
-- the claim is refused, or the claim lands first and the restore leaves that attachment deleted.
-- Both are consistent. What can no longer happen is a live row with no object behind it.

ALTER TABLE public.note_attachments
    ADD COLUMN IF NOT EXISTS purge_claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN public.note_attachments.purge_claimed_at IS
    'Set when a sweeper claimed this row for permanent deletion. Its R2 object is being or has '
    'been deleted, so restore must not bring the row back to live.';

/**
 * Claims one attachment for permanent deletion, or refuses.
 *
 * Returns the canonical object key only when the claim succeeds, so a sweeper cannot delete
 * bytes it was not granted. Re-claiming an already-claimed row is allowed and returns the same
 * key: the sweeper has to be able to retry after a failed R2 delete.
 */
CREATE OR REPLACE FUNCTION public.claim_orphaned_attachment_for_delete(
    p_owner_id UUID,
    p_note_id TEXT,
    p_attachment_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_row public.note_attachments%ROWTYPE;
BEGIN
    PERFORM public.require_service_role();

    IF p_owner_id IS NULL
       OR NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id)
    THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'invalid');
    END IF;

    -- The lock is the whole point: restore updates this same row, so one of the two waits.
    SELECT * INTO v_row
    FROM public.note_attachments
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'missing');
    END IF;

    -- A restore that got here first cleared deleted_at. The note being live again means the
    -- attachment is live again, and its bytes must stay.
    IF v_row.deleted_at IS NULL THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'restored');
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'note_live');
    END IF;

    IF v_row.deleted_at >= timezone('utc', now()) - interval '24 hours' THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'retention');
    END IF;

    IF v_row.purge_requested_at IS NULL AND NOT EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'not_tombstoned');
    END IF;

    -- note_attachments is behind a mutation guard, so every write has to declare itself the
    -- same way the other RPCs do. Without this the claim raised "direct table mutation not
    -- allowed" and no attachment could ever be claimed for deletion.
    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments
    SET purge_claimed_at = COALESCE(purge_claimed_at, timezone('utc', now()))
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;
    PERFORM public.end_sync_mutation();

    RETURN jsonb_build_object(
        'claimed', true,
        'object_key', public.expected_attachment_object_key(p_owner_id, v_note_id, v_attachment_id)
    );
END;
$$;

-- Purging now requires the claim, so metadata can only disappear after deletion was granted.
CREATE OR REPLACE FUNCTION public.purge_orphaned_deleted_attachment(
    p_owner_id UUID,
    p_note_id TEXT,
    p_attachment_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_deleted INTEGER := 0;
BEGIN
    PERFORM public.require_service_role();

    IF p_owner_id IS NULL
       OR NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id)
    THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'invalid');
    END IF;

    PERFORM public.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND purge_claimed_at IS NOT NULL;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    PERFORM public.end_sync_mutation();

    IF v_deleted = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_claimed');
    END IF;

    RETURN jsonb_build_object('status', 'applied');
END;
$$;

-- Restore must not resurrect an attachment whose bytes a sweeper has already claimed. Redefined
-- in full because the un-delete is inlined in restore_note; only the attachment UPDATE changes.
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

    PERFORM public.validate_note_payload(
        p_title, p_content, p_color, p_position, p_client_timestamp,
        p_reminder_timestamp, COALESCE(p_labels, '[]'::jsonb), COALESCE(p_checklist, '[]'::jsonb)
    );

    PERFORM 1 FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    DELETE FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = p_note_id;

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    v_new_revision := nextval('public.sync_revision_seq');

    IF FOUND THEN
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
        WHERE owner_id = v_owner AND note_id = p_note_id;
    ELSE
        INSERT INTO public.notes (
            note_id, local_id, owner_id, revision, title, content, client_timestamp,
            color, is_pinned, is_archived, is_trashed, position, reminder_timestamp,
            labels, checklist, server_updated_at
        ) VALUES (
            p_note_id, p_local_id, v_owner, v_new_revision, p_title, p_content, p_client_timestamp,
            p_color, p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp,
            COALESCE(p_labels, '[]'::jsonb), COALESCE(p_checklist, '[]'::jsonb), v_server_updated_at
        );
    END IF;

    -- purge_claimed_at means a sweeper already started deleting this attachment's bytes.
    -- Bringing the row back to live would leave metadata pointing at an object that is gone,
    -- so a claimed attachment stays deleted even though its note is restored.
    UPDATE public.note_attachments
    SET deleted_at = NULL,
        purge_requested_at = NULL
    WHERE owner_id = v_owner
      AND note_id = p_note_id
      AND deleted_at IS NOT NULL
      AND purge_claimed_at IS NULL;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

REVOKE ALL ON FUNCTION public.claim_orphaned_attachment_for_delete(UUID, TEXT, TEXT)
    FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.claim_orphaned_attachment_for_delete(UUID, TEXT, TEXT) TO service_role;
