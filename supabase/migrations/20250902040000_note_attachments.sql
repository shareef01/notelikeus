-- Phase 8: attachment metadata in Postgres; binary payload in Cloudflare R2 (via Worker).

CREATE TABLE public.note_attachments (
    attachment_id TEXT NOT NULL,
    owner_id UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    note_id TEXT NOT NULL,
    object_key TEXT NOT NULL,
    mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    attachment_type TEXT NOT NULL DEFAULT 'image',
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT note_attachments_pkey PRIMARY KEY (owner_id, attachment_id),
    CONSTRAINT note_attachments_object_key_unique UNIQUE (object_key),
    CONSTRAINT note_attachments_id_nonempty CHECK (char_length(trim(attachment_id)) > 0),
    CONSTRAINT note_attachments_note_id_nonempty CHECK (char_length(trim(note_id)) > 0),
    CONSTRAINT note_attachments_object_key_nonempty CHECK (char_length(trim(object_key)) > 0)
);

CREATE INDEX note_attachments_owner_note_idx
    ON public.note_attachments (owner_id, note_id)
    WHERE deleted_at IS NULL;

ALTER TABLE public.note_attachments ENABLE ROW LEVEL SECURITY;

CREATE POLICY note_attachments_select_own ON public.note_attachments
    FOR SELECT TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY note_attachments_insert_own ON public.note_attachments
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY note_attachments_update_own ON public.note_attachments
    FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY note_attachments_delete_own ON public.note_attachments
    FOR DELETE TO authenticated
    USING (owner_id = auth.uid());

CREATE OR REPLACE FUNCTION public.expected_attachment_object_key(
    p_owner_id UUID,
    p_note_id TEXT,
    p_attachment_id TEXT
)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
    SELECT format(
        'owners/%s/notes/%s/%s',
        p_owner_id::text,
        trim(p_note_id),
        trim(p_attachment_id)
    );
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

    RETURN jsonb_build_object(
        'attachment_id', trim(p_attachment_id),
        'note_id', trim(p_note_id),
        'object_key', v_key
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.list_note_attachments(p_note_id TEXT)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
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
      AND a.note_id = trim(p_note_id)
      AND a.deleted_at IS NULL;
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
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    UPDATE public.note_attachments
    SET deleted_at = timezone('utc', now())
    WHERE owner_id = v_owner
      AND attachment_id = trim(p_attachment_id)
      AND note_id = trim(p_note_id)
      AND deleted_at IS NULL;

    RETURN jsonb_build_object(
        'deleted', found,
        'attachment_id', trim(p_attachment_id)
    );
END;
$$;
