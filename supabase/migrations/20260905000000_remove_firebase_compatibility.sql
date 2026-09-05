-- Remove Firebase identity-mapping objects. Legacy Firebase user/data migration is abandoned;
-- Supabase auth.users.id is the only cloud owner id.

DROP FUNCTION IF EXISTS public.link_verified_firebase_uid(TEXT, UUID);
DROP FUNCTION IF EXISTS public.firebase_uid_proven_elsewhere(TEXT, UUID);
DROP FUNCTION IF EXISTS public.get_firebase_uid_link();
DROP FUNCTION IF EXISTS public.link_firebase_uid(TEXT);
DROP FUNCTION IF EXISTS public.get_linked_firebase_uid();
DROP TABLE IF EXISTS public.firebase_uid_mappings CASCADE;

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

REVOKE ALL ON FUNCTION public.delete_all_user_cloud_data() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_all_user_cloud_data() TO authenticated;

-- Owner-only undo of a permanent delete. Anti-resurrection still applies to ordinary
-- apply_note_change; this is the explicit restore path that used to delete a Firestore tombstone.
CREATE OR REPLACE FUNCTION public.clear_note_tombstone(p_note_id TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_deleted INTEGER := 0;
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    DELETE FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = trim(p_note_id);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object('cleared', v_deleted > 0, 'note_id', trim(p_note_id));
END;
$$;

REVOKE ALL ON FUNCTION public.clear_note_tombstone(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.clear_note_tombstone(TEXT) TO authenticated;
