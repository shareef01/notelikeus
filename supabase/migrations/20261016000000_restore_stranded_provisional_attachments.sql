-- Restore the images Android 2.0.0 stranded (N-01, recovery).
--
-- 2.0.0 pushed a new note before its image had uploaded, uploaded the image afterwards as a
-- provisional row, and never committed the note again, so no commit named the row. Provisional rows
-- do not hydrate, so the image disappeared from every device, and the next download released the local
-- bytes. The R2 object is the only copy left. 2.1.0 recommits such notes, but it cannot recover rows
-- that 2.0.0 already stranded: the device no longer has the reference.
--
-- 20261015000000 keeps the sweep away from these rows for 90 days. This promotes them, so every client
-- hydrates the image back into its note on the next sync. A provisional row cannot be told apart from
-- a genuine orphan (an image removed before its note ever committed), so a few of those come back too.
-- That is recoverable by deleting the image again, unlike a lost one.
--
-- Eligible: provisional, live and unclaimed, uploaded more than a day ago (a current client commits
-- its own uploads within the same sync), on a note that still exists. The per-note limit of 20 counts
-- provisional rows already, so promoting cannot exceed it, and quota counts them too.
--
-- The function exists so the rule can be tested. Nobody but the database owner may run it: it acts on
-- every account at once, and the migration below is its only caller.

CREATE OR REPLACE FUNCTION public.restore_stranded_provisional_attachments(p_uploaded_before TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_restored INTEGER;
BEGIN
    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments a
    SET committed_at = a.created_at
    WHERE a.committed_at IS NULL
      AND a.deleted_at IS NULL
      AND a.delete_claimed_at IS NULL
      AND a.purge_claimed_at IS NULL
      AND a.created_at < p_uploaded_before
      AND EXISTS (
          SELECT 1 FROM public.notes n
          WHERE n.owner_id = a.owner_id
            AND n.note_id = a.note_id
      );
    GET DIAGNOSTICS v_restored = ROW_COUNT;
    PERFORM public.end_sync_mutation();
    RETURN v_restored;
END;
$$;

REVOKE ALL ON FUNCTION public.restore_stranded_provisional_attachments(TIMESTAMPTZ)
    FROM PUBLIC, anon, authenticated, service_role;

DO $$
DECLARE
    v_restored INTEGER := public.restore_stranded_provisional_attachments(
        timezone('utc', now()) - interval '1 day'
    );
BEGIN
    RAISE NOTICE 'Restored % stranded provisional attachment(s).', v_restored;
END;
$$;
