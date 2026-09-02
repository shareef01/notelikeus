-- Phase 11: account wipe for "sign out and delete cloud data".
-- Hard-deletes the caller's notes, tombstones, attachment metadata, sync meta, and uid mapping.
-- Does not remove Firebase; production clients still default to Firebase.

CREATE POLICY firebase_uid_mappings_update_own ON public.firebase_uid_mappings
    FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY firebase_uid_mappings_delete_own ON public.firebase_uid_mappings
    FOR DELETE TO authenticated
    USING (owner_id = auth.uid());

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
    PERFORM set_config('notelikeus.sync_mutation', '1', true);

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    SELECT COALESCE(array_agg(object_key), ARRAY[]::text[])
    INTO v_object_keys
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND deleted_at IS NULL;

    DELETE FROM public.note_attachments WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_attachments = ROW_COUNT;

    DELETE FROM public.notes WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    DELETE FROM public.note_tombstones WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_tombstones = ROW_COUNT;

    DELETE FROM public.sync_meta WHERE owner_id = v_owner;
    DELETE FROM public.firebase_uid_mappings WHERE owner_id = v_owner;

    RETURN jsonb_build_object(
        'status', 'applied',
        'notes_deleted', v_notes,
        'tombstones_deleted', v_tombstones,
        'attachments_deleted', v_attachments,
        'attachment_object_keys', to_jsonb(v_object_keys)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.delete_all_user_cloud_data() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_all_user_cloud_data() TO authenticated;
