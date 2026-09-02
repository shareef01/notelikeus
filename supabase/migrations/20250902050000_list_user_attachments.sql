-- Phase 9: bulk attachment metadata for sync hydration.

CREATE OR REPLACE FUNCTION public.list_user_attachments()
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
      AND a.deleted_at IS NULL;
$$;
