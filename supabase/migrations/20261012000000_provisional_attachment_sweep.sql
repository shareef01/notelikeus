-- F-09: reclaim provisional attachment rows that no note commit will ever promote.
--
-- R19.1 made an upload's metadata provisional (committed_at IS NULL) until the note that references it
-- commits. Nothing ever removed a provisional row whose note continuation went stale, yet every such row
-- still counts against the owner's 200 MB quota and 20-per-note limit, and its R2 bytes are never
-- deleted. Over time an account can hit "quota exceeded" with no visible attachments.
--
-- A provisional row is not by itself an orphan: it may be an upload whose note commit simply has not
-- happened yet (a device that went offline between the two). Deleting that one destroys an image the
-- user still has. So a row is claimed only on positive evidence that no commit will name it, after a
-- long grace period: its note was committed again *after* the upload without naming it (the commit
-- would otherwise have promoted it), and that later commit is itself older than the grace period -- so
-- a client whose commit was refused (for example on a revision conflict with another device) has had
-- that long to retry and name it.
--
-- A row whose note has not been committed since the upload is left alone indefinitely. Rows of a
-- deleted note need nothing here: apply_note_delete already marks every attachment of the note
-- deleted, provisional ones included, and the orphan sweep takes them from there.
--
-- Claiming marks the row deleted and sets delete_claimed_at, in one statement under row locks. That is
-- the state an owner's DELETE leaves behind, so the existing unconfirmed-delete sweep
-- (list_unconfirmed_attachment_deletes -> R2 delete -> confirm_attachment_object_deleted) removes the
-- bytes, and the terminal-delete gate stops a later re-upload or note commit from reviving the identity.

CREATE INDEX IF NOT EXISTS note_attachments_provisional_idx
    ON public.note_attachments (created_at)
    WHERE committed_at IS NULL AND deleted_at IS NULL;

CREATE OR REPLACE FUNCTION public.claim_orphaned_provisional_attachments(
    p_limit INTEGER DEFAULT 50,
    p_min_age INTERVAL DEFAULT interval '14 days'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 50), 100));
    -- Never shorter than a day, whatever the caller asks: this deletes user data.
    v_min_age INTERVAL := GREATEST(COALESCE(p_min_age, interval '14 days'), interval '1 day');
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
