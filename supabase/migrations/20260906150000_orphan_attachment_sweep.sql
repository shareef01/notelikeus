-- Hosted R2 orphan sweep. User PUT/GET/DELETE stay bearer-only.
-- These RPCs are service_role only: the Worker cron has no user JWT.
-- A blob is eligible only when metadata is deleted, the note is tombstoned,
-- the note is not live, and deleted_at is at least 24 hours old — so a
-- same-day restore cannot race the sweeper.

CREATE OR REPLACE FUNCTION public.require_service_role()
RETURNS void
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    IF coalesce(auth.role(), '') IS DISTINCT FROM 'service_role' THEN
        RAISE EXCEPTION 'not authorized' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.list_orphaned_deleted_attachments(
    p_limit INTEGER DEFAULT 50
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 50), 100));
BEGIN
    PERFORM public.require_service_role();
    RETURN coalesce((
        SELECT jsonb_agg(jsonb_build_object(
            'owner_id', a.owner_id,
            'attachment_id', a.attachment_id,
            'note_id', a.note_id,
            'object_key', a.object_key
        ))
        FROM (
            SELECT a.owner_id, a.attachment_id, a.note_id, a.object_key
            FROM public.note_attachments a
            WHERE a.deleted_at IS NOT NULL
              AND a.deleted_at < timezone('utc', now()) - interval '24 hours'
              AND EXISTS (
                  SELECT 1
                  FROM public.note_tombstones t
                  WHERE t.owner_id = a.owner_id AND t.note_id = a.note_id
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM public.notes n
                  WHERE n.owner_id = a.owner_id AND n.note_id = a.note_id
              )
            ORDER BY a.deleted_at ASC
            LIMIT v_limit
        ) a
    ), '[]'::jsonb);
END;
$$;

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

    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'note_live');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_tombstoned');
    END IF;

    PERFORM public.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND deleted_at IS NOT NULL
      AND deleted_at < timezone('utc', now()) - interval '24 hours';
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    PERFORM public.end_sync_mutation();

    IF v_deleted = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_eligible');
    END IF;

    RETURN jsonb_build_object('status', 'applied');
END;
$$;

REVOKE ALL ON FUNCTION public.require_service_role() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.list_orphaned_deleted_attachments(INTEGER) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.purge_orphaned_deleted_attachment(UUID, TEXT, TEXT) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.require_service_role() TO service_role;
GRANT EXECUTE ON FUNCTION public.list_orphaned_deleted_attachments(INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.purge_orphaned_deleted_attachment(UUID, TEXT, TEXT) TO service_role;
