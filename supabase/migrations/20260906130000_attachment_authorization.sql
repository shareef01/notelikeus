-- Harden attachment metadata and expose Worker authorization RPCs.
-- User bearer + RLS/RPC only; no service-role path.

CREATE OR REPLACE FUNCTION public.is_allowed_attachment_mime(p_mime TEXT)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
    SELECT lower(split_part(trim(p_mime), ';', 1)) IN (
        'image/png',
        'image/jpeg',
        'image/jpg',
        'image/webp',
        'image/gif',
        'image/heic',
        'image/heif',
        'image/avif'
    );
$$;

CREATE OR REPLACE FUNCTION public.is_valid_attachment_path_id(p_id TEXT)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
    SELECT p_id ~ '^[A-Za-z0-9._-]{1,128}$';
$$;

CREATE OR REPLACE FUNCTION public.assert_live_owned_note(p_note_id TEXT)
RETURNS void
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF NOT public.is_valid_attachment_path_id(trim(p_note_id)) THEN
        RAISE EXCEPTION 'invalid note id' USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = trim(p_note_id)
    ) THEN
        RAISE EXCEPTION 'note is tombstoned' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = trim(p_note_id)
    ) THEN
        RAISE EXCEPTION 'note not found' USING ERRCODE = '22023';
    END IF;
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
    v_key TEXT;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    BEGIN
        PERFORM public.register_note_attachment(
            v_attachment_id,
            v_note_id,
            public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id),
            p_mime_type,
            p_size_bytes,
            'image'
        );
    EXCEPTION WHEN others THEN
        RETURN jsonb_build_object('allowed', false);
    END;

    v_key := public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id);
    RETURN jsonb_build_object(
        'allowed', true,
        'object_key', v_key,
        'max_bytes', 10485760
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.authorize_note_attachment_get(
    p_note_id TEXT,
    p_attachment_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_key TEXT;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    SELECT object_key INTO v_key
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = trim(p_note_id)
      AND attachment_id = trim(p_attachment_id)
      AND deleted_at IS NULL;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    RETURN jsonb_build_object('allowed', true, 'object_key', v_key);
END;
$$;

CREATE OR REPLACE FUNCTION public.authorize_note_attachment_delete(
    p_note_id TEXT,
    p_attachment_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_key TEXT;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;
    IF NOT public.is_valid_attachment_path_id(trim(p_note_id))
       OR NOT public.is_valid_attachment_path_id(trim(p_attachment_id)) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    v_key := public.expected_attachment_object_key(v_owner, trim(p_note_id), trim(p_attachment_id));

    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = timezone('utc', now())
    WHERE owner_id = v_owner
      AND note_id = trim(p_note_id)
      AND attachment_id = trim(p_attachment_id)
      AND deleted_at IS NULL;
    PERFORM public.end_sync_mutation();

    RETURN jsonb_build_object('allowed', true, 'object_key', v_key);
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
        'attachment_id', attachment_id,
        'note_id', note_id,
        'object_key', object_key
    )), '[]'::jsonb)
    FROM public.note_attachments
    WHERE owner_id = auth.uid()
      AND deleted_at IS NOT NULL;
$$;

REVOKE ALL ON FUNCTION public.is_allowed_attachment_mime(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.is_valid_attachment_path_id(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.assert_live_owned_note(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.register_note_attachment(
    TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT
) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.authorize_note_attachment_get(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.authorize_note_attachment_delete(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.list_pending_deleted_attachments() FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.is_allowed_attachment_mime(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.is_valid_attachment_path_id(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.assert_live_owned_note(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.register_note_attachment(
    TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_get(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_delete(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.list_pending_deleted_attachments() TO authenticated;
