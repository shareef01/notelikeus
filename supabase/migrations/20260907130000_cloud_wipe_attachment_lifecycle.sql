-- Make "delete all cloud data" actually delete the attachment bytes.
--
-- The wipe hard-deleted every note_attachments row and returned only the keys of rows that were
-- still live. Three consequences, all silent:
--
--   1. authorize_note_attachment_delete requires a metadata row, so once the wipe had removed
--      every row the Worker refused every subsequent DELETE with 404 and no R2 object was ever
--      deleted. The client reported a successful wipe regardless.
--   2. Attachments that were already soft-deleted were excluded from the returned keys entirely,
--      so nothing even attempted to delete them.
--   3. list_orphaned_deleted_attachments only sweeps rows whose note has a tombstone, and the
--      wipe deletes tombstones too — so the safety net could not find the leftovers either.
--
-- The wipe now soft-deletes attachment metadata and marks it for purge instead of destroying it.
-- Authorization keeps working, the client deletes the bytes, and finalize_cloud_wipe removes the
-- rows once the objects are gone. If the client dies in between, the sweeper finds the marked
-- rows and finishes the job. Retrying is idempotent: a retry re-reads whatever is still marked.

ALTER TABLE public.note_attachments
    ADD COLUMN IF NOT EXISTS purge_requested_at TIMESTAMPTZ;

COMMENT ON COLUMN public.note_attachments.purge_requested_at IS
    'Set by delete_all_user_cloud_data. Marks a row whose R2 object must be deleted even though '
    'its note and tombstone are gone, so the orphan sweeper can still find it after a wipe.';

-- Sweeping the wipe leftovers needs an index that does not depend on a tombstone existing.
CREATE INDEX IF NOT EXISTS note_attachments_purge_requested_idx
    ON public.note_attachments (purge_requested_at)
    WHERE purge_requested_at IS NOT NULL;

CREATE OR REPLACE FUNCTION public.delete_all_user_cloud_data()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_notes INTEGER := 0;
    v_tombstones INTEGER := 0;
    v_attachments INTEGER := 0;
    v_object_keys TEXT[] := ARRAY[]::text[];
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    -- Every object the user owns, whether or not it was already soft-deleted. The previous
    -- `deleted_at IS NULL` filter is what stranded already-deleted attachments in R2.
    SELECT COALESCE(array_agg(object_key), ARRAY[]::text[])
    INTO v_object_keys
    FROM public.note_attachments
    WHERE owner_id = v_owner;

    -- Marked, not destroyed: the Worker still has to authorize each DELETE against this row,
    -- and the sweeper still has to be able to find it if the client never comes back.
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        purge_requested_at = COALESCE(purge_requested_at, timezone('utc', now()))
    WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_attachments = ROW_COUNT;

    DELETE FROM public.notes WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    DELETE FROM public.note_tombstones WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_tombstones = ROW_COUNT;

    DELETE FROM public.sync_meta WHERE owner_id = v_owner;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'notes_deleted', v_notes,
        'tombstones_deleted', v_tombstones,
        'attachments_deleted', v_attachments,
        'attachment_object_keys', to_jsonb(v_object_keys)
    );
END;
$$;

/**
 * Second phase of the wipe: drop the metadata once its R2 objects are confirmed gone.
 *
 * Only touches rows this owner's wipe marked, so it cannot be used to erase attachment history
 * that a wipe did not request. Safe to call twice, and safe never to call — the sweeper covers
 * a client that dies before reaching it.
 */
CREATE OR REPLACE FUNCTION public.finalize_cloud_wipe()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_purged INTEGER := 0;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    PERFORM public.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = v_owner
      AND purge_requested_at IS NOT NULL;
    GET DIAGNOSTICS v_purged = ROW_COUNT;
    PERFORM public.end_sync_mutation();

    RETURN jsonb_build_object('status', 'applied', 'attachments_purged', v_purged);
END;
$$;

-- Sweeper: also collect rows a wipe marked. Those have no note and no tombstone by definition,
-- so the tombstone requirement that guards ordinary deletions cannot apply to them.
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
              AND NOT EXISTS (
                  SELECT 1
                  FROM public.notes n
                  WHERE n.owner_id = a.owner_id AND n.note_id = a.note_id
              )
              AND (
                  a.purge_requested_at IS NOT NULL
                  OR EXISTS (
                      SELECT 1
                      FROM public.note_tombstones t
                      WHERE t.owner_id = a.owner_id AND t.note_id = a.note_id
                  )
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
    v_purge_requested BOOLEAN := false;
    v_deleted INTEGER := 0;
BEGIN
    PERFORM public.require_service_role();

    IF p_owner_id IS NULL
       OR NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id)
    THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'invalid');
    END IF;

    -- A live note always wins: restoring a note must never lose to a sweep that was queued
    -- before the restore happened.
    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'note_live');
    END IF;

    SELECT purge_requested_at IS NOT NULL INTO v_purge_requested
    FROM public.note_attachments
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;

    IF NOT COALESCE(v_purge_requested, false) AND NOT EXISTS (
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

REVOKE ALL ON FUNCTION public.delete_all_user_cloud_data() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.finalize_cloud_wipe() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.list_orphaned_deleted_attachments(INTEGER) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.purge_orphaned_deleted_attachment(UUID, TEXT, TEXT) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.delete_all_user_cloud_data() TO authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_cloud_wipe() TO authenticated;
GRANT EXECUTE ON FUNCTION public.list_orphaned_deleted_attachments(INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.purge_orphaned_deleted_attachment(UUID, TEXT, TEXT) TO service_role;
