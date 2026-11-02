-- Audit of 2026-11-02, AUD-003 and AUD-007: an attachment row now outlives its R2 object, and a
-- cloud wipe serialises with the writers it races.
--
-- 1. The quota could be reset while the bytes stayed in R2 (AUD-003)
--
--    The quota sums `note_attachments` rows, so a row removed or discounted while its object is still in
--    the bucket is storage nobody pays for and, because the sweeps start from rows, storage nothing ever
--    reclaims. Four paths any signed-in caller could take did exactly that:
--
--      * `finalize_cloud_wipe()` deleted every row the wipe marked, whether or not its object was gone.
--        `delete_all_user_cloud_data()` then `finalize_cloud_wipe()`, without the Worker DELETEs in
--        between, emptied the quota and kept every byte.
--      * `purge_deleted_note_attachment()` deleted a soft-deleted row on a note that is not live, again
--        without asking whether its object was gone.
--      * `finalize_note_attachment_delete()` stamped `object_deleted_at` itself. The caller is the user
--        (the Worker forwards their token), so the one column that says "the bytes are gone" was a value
--        the user could set without deleting anything, and the quota stopped counting the row.
--      * `attachment_stored_bytes()` left out a soft-deleted, unclaimed row on a live note. Deleting a
--        note, `clear_note_tombstone()`, then re-creating the same note id made one.
--
--    The invariant from here on, enforced by the database rather than by any client or the Worker:
--
--      A row counts against its owner's quota until its object is confirmed deleted, and only the
--      service role can confirm that.
--
--    Concretely: `object_deleted_at` is written only by `confirm_attachment_object_deleted()` (service
--    role, already the cron's path, now the Worker's DELETE path too); the two user-callable purges
--    remove only confirmed rows; and the quota counts every unconfirmed row. Nothing is deleted by this
--    migration and no deletion becomes more aggressive: rows the user could previously drop early now
--    wait for the sweeps that already exist, which delete the object first and the row second.
--
--    How every unconfirmed row still gets reclaimed, so this does not turn into quota lost for good:
--      * claimed (`delete_claimed_at`, every Worker DELETE): `list_unconfirmed_attachment_deletes`
--        re-deletes the object after an hour and confirms it.
--      * marked by a wipe (`purge_requested_at`) or on a tombstoned note: the orphan sweep claims,
--        deletes and purges after 24 hours, as before.
--      * soft-deleted and unclaimed on a note that exists again (the clear-tombstone path above, and rows
--        from before claims existed): still counted, and reclaimed by a wipe or an account deletion.
--        Counting them over-states storage; not counting them is the bypass. The conservative side wins.
--
-- 2. A wipe did not take the per-owner locks (AUD-007)
--
--    `delete_all_user_cloud_data()` took neither `note_sync_write:` nor `note_attachment_quota:`. A note
--    insert running concurrently passed the wipe-generation trigger (the wipe had not committed), and the
--    wipe's DELETE could not see the uncommitted row, so the note outlived the wipe that was meant to
--    fence it. A concurrent upload finalization likewise left a row the wipe never marked. The wipe now
--    takes both, in the order `request_account_deletion()` uses (write, then quota); no function takes
--    them the other way round, so the ordering cannot deadlock.
--
-- Rollout: either order. The deployed Worker calls `finalize_note_attachment_delete()`, which still
-- answers and still claims, now with `confirmed: false`; the hourly sweep confirms those deletes. The
-- Worker that ships with this confirms through the service role straight away, and works against the
-- previous schema too, because `confirm_attachment_object_deleted()` already exists there. A deployment
-- with no SUPABASE_SERVICE_ROLE_KEY can no longer release quota for deleted attachments: it already could
-- not delete accounts or sweep, and an unconfirmed row is exactly what it cannot prove is gone.
--
-- Rollback: restore `attachment_stored_bytes` from 20261102000000 and the four other bodies from
-- 20261019000000. No data changes either way.

/*
 * Bytes this owner holds in storage, leaving out one attachment id (the one being re-uploaded).
 *
 * Every row whose object has not been confirmed deleted. Which flags a row carries says why it is going
 * away, not whether its bytes have, so none of them may take it out of the sum.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.attachment_stored_bytes(
    p_owner UUID,
    p_excluding_attachment_id TEXT
)
RETURNS BIGINT
LANGUAGE sql
STABLE
SET search_path = public
AS $$
    SELECT coalesce(sum(a.size_bytes), 0)::bigint
    FROM public.note_attachments a
    WHERE a.owner_id = p_owner
      AND a.attachment_id <> p_excluding_attachment_id
      AND a.object_deleted_at IS NULL;
$$;

/*
 * Drops the rows a wipe marked, once their objects are confirmed gone.
 *
 * A marked row whose object is unconfirmed stays: the caller's Worker DELETE may have failed, or never
 * happened at all, and the row is the only thing that still knows where those bytes are. The orphan sweep
 * removes it later, object first. `attachments_pending` is how many are still waiting.
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
    v_pending INTEGER := 0;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    PERFORM notelikeus_internal.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = v_owner
      AND purge_requested_at IS NOT NULL
      AND object_deleted_at IS NOT NULL;
    GET DIAGNOSTICS v_purged = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    SELECT count(*)::integer INTO v_pending
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND purge_requested_at IS NOT NULL;

    RETURN jsonb_build_object(
        'status', 'applied',
        'attachments_purged', v_purged,
        'attachments_pending', v_pending
    );
END;
$$;

/*
 * The client sweep's purge of one soft-deleted row, now only once its object is confirmed gone.
 *
 * A refusal is a value, not an error, so a client sweep that ran ahead of the confirmation simply finds
 * the row again on its next pass.
 */
CREATE OR REPLACE FUNCTION public.purge_deleted_note_attachment(
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
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_deleted INTEGER := 0;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'note_live');
    END IF;

    PERFORM notelikeus_internal.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND deleted_at IS NOT NULL
      AND object_deleted_at IS NOT NULL;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    IF v_deleted = 0 AND EXISTS (
        SELECT 1 FROM public.note_attachments
        WHERE owner_id = v_owner AND note_id = v_note_id AND attachment_id = v_attachment_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'object_unconfirmed');
    END IF;

    RETURN jsonb_build_object('status', 'applied');
END;
$$;

/*
 * The user-token half of a Worker DELETE: records the claim, and no longer the confirmation.
 *
 * `object_deleted_at` is what releases quota, and this runs with the user's own token, so the user could
 * call it without deleting anything. The Worker now confirms through `confirm_attachment_object_deleted`
 * with the service role; a Worker that still calls this gets `confirmed: false`, which it already
 * reports as "the sweep will confirm it", and the hourly unconfirmed-delete pass does.
 */
CREATE OR REPLACE FUNCTION public.finalize_note_attachment_delete(
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
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_confirmed BOOLEAN := false;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RAISE EXCEPTION 'invalid attachment or note id' USING ERRCODE = '22023';
    END IF;

    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        delete_claimed_at = COALESCE(delete_claimed_at, timezone('utc', now()))
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
    RETURNING object_deleted_at IS NOT NULL INTO v_confirmed;
    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object(
        'allowed', true,
        -- True only when the service role already confirmed it (a repeated DELETE).
        'confirmed', COALESCE(v_confirmed, false),
        'object_key', public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id)
    );
END;
$$;

-- Unchanged from 20261019000000 apart from the two locks at the top (AUD-007).
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
    v_generation BIGINT := 0;
    v_object_keys TEXT[] := ARRAY[]::text[];
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    -- Same order as request_account_deletion: a note write or an upload finalization in progress
    -- commits first and is wiped below, and one that was waiting sees the new generation (NL002) or a
    -- note that is gone.
    PERFORM pg_advisory_xact_lock(hashtextextended('note_sync_write:' || v_owner::text, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('note_attachment_quota:' || v_owner::text, 0));

    INSERT INTO public.account_sync_state (owner_id, wipe_generation, wiped_at)
    VALUES (v_owner, 1, timezone('utc', now()))
    ON CONFLICT (owner_id) DO UPDATE
        SET wipe_generation = public.account_sync_state.wipe_generation + 1,
            wiped_at = timezone('utc', now())
    RETURNING wipe_generation INTO v_generation;

    PERFORM public.present_wipe_generation(v_generation);

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

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'notes_deleted', v_notes,
        'tombstones_deleted', v_tombstones,
        'attachments_deleted', v_attachments,
        'attachment_object_keys', to_jsonb(v_object_keys),
        'wipe_generation', v_generation
    );
END;
$$;
