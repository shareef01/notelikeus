-- Atomic restore, shared payload validation, revision lookup, and delete-time
-- attachment metadata tombstoning. Does not rewrite historical migrations.

CREATE OR REPLACE FUNCTION public.validate_note_payload(
    p_title TEXT,
    p_content TEXT,
    p_color INTEGER,
    p_position INTEGER,
    p_client_timestamp BIGINT,
    p_reminder_timestamp BIGINT,
    p_labels JSONB,
    p_checklist JSONB
)
RETURNS void
LANGUAGE plpgsql
IMMUTABLE
SET search_path = public
AS $$
DECLARE
    v_label JSONB;
    v_item JSONB;
    v_name TEXT;
    v_text TEXT;
    v_pos INTEGER;
BEGIN
    IF p_title IS NULL OR char_length(p_title) > 2000 THEN
        RAISE EXCEPTION 'note title exceeds 2000 characters' USING ERRCODE = '22023';
    END IF;
    IF p_content IS NULL OR char_length(p_content) > 100000 THEN
        RAISE EXCEPTION 'note content exceeds 100000 characters' USING ERRCODE = '22023';
    END IF;
    IF p_color IS NULL THEN
        RAISE EXCEPTION 'note color is required' USING ERRCODE = '22023';
    END IF;
    IF p_position IS NULL OR p_position < -1000000000 OR p_position > 1000000000 THEN
        RAISE EXCEPTION 'note position out of range' USING ERRCODE = '22023';
    END IF;
    IF p_client_timestamp IS NULL OR p_client_timestamp < 0 OR p_client_timestamp > 4102444800000 THEN
        RAISE EXCEPTION 'note timestamp out of range' USING ERRCODE = '22023';
    END IF;
    IF p_reminder_timestamp IS NOT NULL
       AND (p_reminder_timestamp < 0 OR p_reminder_timestamp > 4102444800000) THEN
        RAISE EXCEPTION 'reminder timestamp out of range' USING ERRCODE = '22023';
    END IF;

    IF p_labels IS NULL OR jsonb_typeof(p_labels) <> 'array' THEN
        RAISE EXCEPTION 'labels must be a JSON array' USING ERRCODE = '22023';
    END IF;
    IF jsonb_array_length(p_labels) > 100 THEN
        RAISE EXCEPTION 'note has too many labels (max 100)' USING ERRCODE = '22023';
    END IF;
    FOR v_label IN SELECT value FROM jsonb_array_elements(p_labels)
    LOOP
        IF jsonb_typeof(v_label) <> 'object' THEN
            RAISE EXCEPTION 'label must be an object' USING ERRCODE = '22023';
        END IF;
        IF jsonb_typeof(v_label -> 'name') IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'label name must be a string' USING ERRCODE = '22023';
        END IF;
        v_name := v_label ->> 'name';
        IF v_name IS NULL OR char_length(v_name) = 0 OR char_length(v_name) > 2000 THEN
            RAISE EXCEPTION 'label name length invalid' USING ERRCODE = '22023';
        END IF;
    END LOOP;

    IF p_checklist IS NULL OR jsonb_typeof(p_checklist) <> 'array' THEN
        RAISE EXCEPTION 'checklist must be a JSON array' USING ERRCODE = '22023';
    END IF;
    IF jsonb_array_length(p_checklist) > 500 THEN
        RAISE EXCEPTION 'note has too many checklist items (max 500)' USING ERRCODE = '22023';
    END IF;
    FOR v_item IN SELECT value FROM jsonb_array_elements(p_checklist)
    LOOP
        IF jsonb_typeof(v_item) <> 'object' THEN
            RAISE EXCEPTION 'checklist item must be an object' USING ERRCODE = '22023';
        END IF;
        IF jsonb_typeof(v_item -> 'text') IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'checklist item text must be a string' USING ERRCODE = '22023';
        END IF;
        v_text := v_item ->> 'text';
        IF v_text IS NULL OR char_length(v_text) > 2000 THEN
            RAISE EXCEPTION 'checklist item text exceeds 2000 characters' USING ERRCODE = '22023';
        END IF;
        IF (v_item -> 'isChecked') IS DISTINCT FROM 'true'::jsonb
           AND (v_item -> 'isChecked') IS DISTINCT FROM 'false'::jsonb THEN
            RAISE EXCEPTION 'checklist item isChecked must be boolean' USING ERRCODE = '22023';
        END IF;
        BEGIN
            v_pos := (v_item ->> 'position')::integer;
        EXCEPTION WHEN others THEN
            RAISE EXCEPTION 'checklist item position must be an integer' USING ERRCODE = '22023';
        END;
        IF v_pos IS NULL OR v_pos < -1000000 OR v_pos > 1000000 THEN
            RAISE EXCEPTION 'checklist item position out of range' USING ERRCODE = '22023';
        END IF;
    END LOOP;
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

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.lookup_note_revision(p_note_id TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
STABLE
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_revision BIGINT;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    SELECT revision INTO v_revision
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = trim(p_note_id);

    IF FOUND THEN
        RETURN jsonb_build_object('exists', true, 'tombstoned', false, 'revision', v_revision);
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = trim(p_note_id)
    ) THEN
        RETURN jsonb_build_object('exists', false, 'tombstoned', true, 'revision', NULL);
    END IF;

    RETURN jsonb_build_object('exists', false, 'tombstoned', false, 'revision', NULL);
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
            UPDATE public.note_attachments
            SET deleted_at = COALESCE(deleted_at, v_deleted_at)
            WHERE owner_id = v_owner AND note_id = p_note_id AND deleted_at IS NULL;
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

    UPDATE public.note_attachments
    SET deleted_at = v_deleted_at
    WHERE owner_id = v_owner AND note_id = p_note_id AND deleted_at IS NULL;

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

REVOKE ALL ON FUNCTION public.validate_note_payload(
    TEXT, TEXT, INTEGER, INTEGER, BIGINT, BIGINT, JSONB, JSONB
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.restore_note(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.lookup_note_revision(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.apply_note_delete(TEXT, BIGINT) FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.validate_note_payload(
    TEXT, TEXT, INTEGER, INTEGER, BIGINT, BIGINT, JSONB, JSONB
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.restore_note(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.lookup_note_revision(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_delete(TEXT, BIGINT) TO authenticated;
