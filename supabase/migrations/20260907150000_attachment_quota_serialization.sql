-- Make attachment quota enforcement safe under concurrent finalization.
--
-- finalize_note_attachment_put counted the owner's live attachments and summed their bytes, then
-- inserted. Under READ COMMITTED two concurrent calls both read the same pre-insert totals, both
-- concluded they fit, and both inserted — so the per-note count and the per-user byte quota could
-- be exceeded by running uploads in parallel. Client-side limits do not help; this is the
-- server-side boundary.
--
-- A per-owner advisory lock held for the transaction makes check-and-insert atomic with respect
-- to the same owner, while leaving different owners completely uncontended.

CREATE OR REPLACE FUNCTION public.finalize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
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
    v_attachment_id TEXT := trim(p_attachment_id);
    v_note_id TEXT := trim(p_note_id);
    v_mime TEXT := lower(split_part(trim(p_mime_type), ';', 1));
    v_type TEXT := coalesce(nullif(trim(p_attachment_type), ''), 'image');
    v_live_count INTEGER;
    v_user_bytes BIGINT;
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
        deleted_at
    )
    VALUES (
        v_attachment_id,
        v_owner,
        v_note_id,
        v_key,
        v_mime,
        p_size_bytes,
        v_type,
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
        'attachment_id', v_attachment_id,
        'note_id', v_note_id,
        'object_key', v_key
    );
END;
$$;
REVOKE ALL ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.authorize_note_attachment_delete(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.finalize_note_attachment_delete(TEXT, TEXT) FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_delete(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_delete(TEXT, TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) TO authenticated;
