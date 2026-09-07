-- Make attachment PUT preflight report whether the attachment is already committed.
--
-- Attachment ids are immutable identities, and the R2 object key derived from one is
-- deterministic. Re-uploading a committed id therefore overwrote the live object, and a
-- finalization failure afterwards ran a compensating delete that destroyed a blob the surviving
-- metadata row still pointed at — a retry could permanently break an attachment that had been
-- fine. The Worker needs to know that a live row exists before it writes, so it can treat the
-- retry as idempotent and confine compensation to bytes the failed request itself created.
--
-- Replacing image content keeps using a NEW attachment id, exactly as before.

CREATE OR REPLACE FUNCTION public.authorize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT
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
    v_mime TEXT := lower(split_part(trim(p_mime_type), ';', 1));
    v_key TEXT;
    v_live_count INTEGER;
    v_user_bytes BIGINT;
    v_live public.note_attachments%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT public.is_allowed_attachment_mime(v_mime) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF p_size_bytes IS NULL OR p_size_bytes < 1 OR p_size_bytes > 10485760 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- Note must exist and must not be tombstoned
    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- Advisory quota checks
    SELECT count(*) INTO v_live_count
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_live_count >= 20 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    SELECT coalesce(sum(size_bytes), 0) INTO v_user_bytes
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_user_bytes + p_size_bytes > 209715200 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    v_key := public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id);

    -- A live row for this exact identity means the bytes behind v_key are already committed.
    SELECT * INTO v_live
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND deleted_at IS NULL;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'allowed', true,
            'already_live', true,
            'object_key', v_key,
            'mime_type', v_live.mime_type,
            'size_bytes', v_live.size_bytes,
            'max_bytes', 10485760
        );
    END IF;

    RETURN jsonb_build_object(
        'allowed', true,
        'already_live', false,
        'object_key', v_key,
        'max_bytes', 10485760
    );
END;
$$;

REVOKE ALL ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) TO authenticated;
