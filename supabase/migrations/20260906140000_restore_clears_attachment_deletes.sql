-- restore_note must revive attachment metadata in the same transaction. Otherwise
-- list_user_attachments / GET auth keep treating the blobs as deleted, and a later
-- pending-GC pass would remove images from a note the user just brought back.
-- list_pending_deleted_attachments only reports rows for tombstoned notes that are
-- not live. purge_deleted_note_attachment drops metadata after a successful R2 delete.

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

    UPDATE public.note_attachments
    SET deleted_at = NULL
    WHERE owner_id = v_owner
      AND note_id = p_note_id
      AND deleted_at IS NOT NULL;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.list_pending_deleted_attachments()
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'attachment_id', a.attachment_id,
        'note_id', a.note_id,
        'object_key', a.object_key
    )), '[]'::jsonb)
    FROM public.note_attachments a
    WHERE a.owner_id = auth.uid()
      AND a.deleted_at IS NOT NULL
      AND EXISTS (
          SELECT 1
          FROM public.note_tombstones t
          WHERE t.owner_id = a.owner_id AND t.note_id = a.note_id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM public.notes n
          WHERE n.owner_id = a.owner_id AND n.note_id = a.note_id
      );
$$;

CREATE OR REPLACE FUNCTION public.purge_deleted_note_attachment(
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
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'note_live');
    END IF;

    PERFORM public.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND deleted_at IS NOT NULL;
    PERFORM public.end_sync_mutation();

    RETURN jsonb_build_object('status', 'applied');
END;
$$;

REVOKE ALL ON FUNCTION public.purge_deleted_note_attachment(TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.purge_deleted_note_attachment(TEXT, TEXT) TO authenticated;
