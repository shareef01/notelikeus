-- Positions are metadata on the committed image. The body and the attachment membership
-- retain their existing wire formats. Older clients keep their current write signatures.
ALTER TABLE public.note_attachments ADD COLUMN placement JSONB;

CREATE OR REPLACE FUNCTION public.apply_note_change_with_placements(
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
    p_wipe_generation BIGINT,
    p_attachment_placements JSONB
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_result JSONB;
    v_positions JSONB := '{}'::jsonb;
    v_entry JSONB;
    v_id TEXT;
    v_digest TEXT;
    v_offset INTEGER;
    v_ordinal BIGINT;
BEGIN
    -- Keeps the owner lock, wipe and deletion fences, revision check, quotas, and provisional
    -- image promotion in their existing code paths. Any refusal below rolls back that change.
    v_result := public.apply_note_change(
        p_note_id, p_local_id, p_base_revision, p_title, p_content, p_client_timestamp,
        p_color, p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp,
        p_labels, p_checklist, p_attachment_ids, p_wipe_generation
    );

    -- A null placement payload preserves existing positions. An empty array clears them.
    IF v_result->>'status' <> 'applied' OR p_attachment_placements IS NULL THEN
        PERFORM notelikeus_internal.end_sync_mutation();
        RETURN v_result;
    END IF;

    IF jsonb_typeof(p_attachment_placements) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'image placements must be an array' USING ERRCODE = '22023';
    END IF;
    IF jsonb_array_length(p_attachment_placements) > 20 THEN
        RAISE EXCEPTION 'too many image placements' USING ERRCODE = '22023';
    END IF;
    IF p_attachment_placements <> '[]'::jsonb THEN
        IF COALESCE(p_checklist, '[]'::jsonb) <> '[]'::jsonb THEN
            RAISE EXCEPTION 'checklist images cannot have placements' USING ERRCODE = '22023';
        END IF;
        v_digest := encode(sha256(convert_to(p_content, 'UTF8')), 'hex');
    END IF;

    FOR v_entry IN SELECT value FROM jsonb_array_elements(p_attachment_placements) LOOP
        IF jsonb_typeof(v_entry->'attachmentId') IS DISTINCT FROM 'string'
            OR jsonb_typeof(v_entry->'bodySha256') IS DISTINCT FROM 'string'
            OR v_entry->>'bodySha256' IS DISTINCT FROM v_digest
            OR jsonb_typeof(v_entry->'offset') IS DISTINCT FROM 'number'
            OR NOT COALESCE((v_entry->>'offset') ~ '^[1-9][0-9]{0,5}$', false)
            OR jsonb_typeof(v_entry->'ordinal') IS DISTINCT FROM 'number'
            OR NOT COALESCE((v_entry->>'ordinal') ~ '^(0|[1-9][0-9]{0,15})$', false)
        THEN
            RAISE EXCEPTION 'invalid image placement' USING ERRCODE = '22023';
        END IF;
        v_id := v_entry->>'attachmentId';
        v_offset := (v_entry->>'offset')::integer;
        v_ordinal := (v_entry->>'ordinal')::bigint;
        -- PostgreSQL text positions count Unicode code points. Internal offsets include the LF
        -- after the block. Contiguous legacy bullet lines are one block on all clients.
        IF v_ordinal > 9007199254740991
            OR v_offset > char_length(p_content)
            OR (v_offset < char_length(p_content) AND (
                substr(p_content, v_offset, 1) <> chr(10)
                OR (substr(p_content, v_offset + 1, 2) = '• '
                    AND split_part(left(p_content, v_offset - 1), chr(10), -1) LIKE '• %')
            ))
        THEN
            RAISE EXCEPTION 'image placement is not at a body boundary' USING ERRCODE = '22023';
        END IF;
        IF v_positions ? v_id OR NOT COALESCE(p_attachment_ids ? v_id, false) OR NOT EXISTS (
            SELECT 1 FROM public.note_attachments a
            WHERE a.owner_id = v_owner AND a.note_id = p_note_id AND a.attachment_id = v_id
              AND a.committed_at IS NOT NULL AND a.deleted_at IS NULL AND a.attachment_type = 'image'
        ) THEN
            RAISE EXCEPTION 'image placement does not name a distinct committed image on this note'
                USING ERRCODE = '22023';
        END IF;
        v_positions := v_positions || jsonb_build_object(v_id, jsonb_build_object(
            'bodySha256', v_digest, 'offset', v_offset, 'ordinal', v_ordinal
        ));
    END LOOP;

    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.note_attachments a
    SET placement = v_positions->a.attachment_id
    WHERE a.owner_id = v_owner AND a.note_id = p_note_id
      AND a.committed_at IS NOT NULL AND a.deleted_at IS NULL
      AND a.placement IS DISTINCT FROM v_positions->a.attachment_id;
    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.apply_note_change_with_placements(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN,
    INTEGER, BIGINT, JSONB, JSONB, JSONB, BIGINT, JSONB
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.apply_note_change_with_placements(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN,
    INTEGER, BIGINT, JSONB, JSONB, JSONB, BIGINT, JSONB
) TO authenticated;

CREATE OR REPLACE FUNCTION public.list_user_attachments()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'attachment_id', a.attachment_id, 'note_id', a.note_id, 'object_key', a.object_key,
            'mime_type', a.mime_type, 'size_bytes', a.size_bytes, 'attachment_type', a.attachment_type,
            'created_at', extract(epoch FROM a.created_at) * 1000
        ) || CASE WHEN a.placement IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('placement', a.placement) END
        ORDER BY a.created_at ASC
    ), '[]'::jsonb)
    FROM public.note_attachments a
    WHERE a.owner_id = auth.uid() AND a.deleted_at IS NULL AND a.committed_at IS NOT NULL;
$$;

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
    PERFORM notelikeus_internal.assert_account_not_deleted(v_owner);
    RETURN jsonb_build_object(
        'wipe_generation', COALESCE((
            SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = v_owner
        ), 0),
        'attachment_placements', true
    );
END;
$$;
