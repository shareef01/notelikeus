-- Lengthen the provisional-attachment sweep's grace period from 14 days to at least 90 (N-01).
--
-- Android 2.0.0 strands some uploads: a note pushed before its image finished uploading is never
-- committed again, so the image's row stays provisional, and the next download drops the local
-- reference and releases the local bytes. The R2 object is then the only copy of that image. Any
-- later edit of the note satisfies claim_orphaned_provisional_attachments' "committed again without
-- naming it" test, so 14 days after that edit the sweep claimed the row and the bytes were deleted.
--
-- The fix (Kotlin: recommit a resumed upload) ships in the next Android release. Until devices
-- running 2.0.0 have updated, a stranded upload cannot be told apart from a genuine orphan, so the
-- floor is raised in the database itself: no Worker version, and no caller-supplied p_min_age, can
-- claim a row sooner than 90 days after both its upload and the unnaming commit. The function is
-- otherwise unchanged from 20261012000000_provisional_attachment_sweep.sql.

CREATE OR REPLACE FUNCTION public.claim_orphaned_provisional_attachments(
    p_limit INTEGER DEFAULT 50,
    p_min_age INTERVAL DEFAULT interval '90 days'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 50), 100));
    -- Never shorter than 90 days, whatever the caller asks: this deletes user data, and the R2 object
    -- may be the only copy of the image (see the header).
    v_min_age INTERVAL := GREATEST(COALESCE(p_min_age, interval '90 days'), interval '90 days');
    v_now TIMESTAMPTZ := timezone('utc', now());
    v_claimed jsonb;
BEGIN
    PERFORM public.require_service_role();
    PERFORM public.begin_sync_mutation();

    -- FOR UPDATE ... SKIP LOCKED: a row a note commit is promoting right now is skipped rather than
    -- waited on, and a row whose promotion committed after this snapshot is re-checked against its new
    -- version, so committed_at IS NULL excludes it.
    WITH candidates AS (
        SELECT a.owner_id, a.attachment_id
        FROM public.note_attachments a
        WHERE a.committed_at IS NULL
          AND a.deleted_at IS NULL
          AND a.delete_claimed_at IS NULL
          AND a.purge_claimed_at IS NULL
          AND a.created_at < v_now - v_min_age
          AND EXISTS (
              SELECT 1 FROM public.notes n
              WHERE n.owner_id = a.owner_id
                AND n.note_id = a.note_id
                AND n.server_updated_at > a.created_at
                AND n.server_updated_at < v_now - v_min_age
          )
        ORDER BY a.created_at ASC
        LIMIT v_limit
        FOR UPDATE OF a SKIP LOCKED
    ),
    claimed AS (
        UPDATE public.note_attachments a
        SET deleted_at = v_now,
            delete_claimed_at = v_now
        FROM candidates c
        WHERE a.owner_id = c.owner_id
          AND a.attachment_id = c.attachment_id
        RETURNING a.owner_id, a.note_id, a.attachment_id, a.object_key
    )
    SELECT coalesce(
        jsonb_agg(jsonb_build_object(
            'owner_id', owner_id,
            'note_id', note_id,
            'attachment_id', attachment_id,
            'object_key', object_key
        )),
        '[]'::jsonb
    )
    INTO v_claimed
    FROM claimed;

    PERFORM public.end_sync_mutation();
    RETURN v_claimed;
END;
$$;

REVOKE ALL ON FUNCTION public.claim_orphaned_provisional_attachments(INTEGER, INTERVAL)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_orphaned_provisional_attachments(INTEGER, INTERVAL)
    TO service_role;
