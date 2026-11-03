-- Audit of 2026-11-02, AUD-005 and AUD-006: deleting an account, or wiping its cloud data, now needs
-- a recent sign-in, and account deletion can only be started by the Worker.
--
-- 1. A bearer token was enough to destroy everything (AUD-005)
--
--    `request_account_deletion()` and `delete_all_user_cloud_data()` checked only `auth.uid()`. The
--    typed "DELETE" lives in the client, so any holder of a live access token (a stolen session, an
--    XSS that got past the CSP, a borrowed unlocked device) could erase the account for good.
--
--    Both now require that this session authenticated within the last ten minutes. The time comes
--    from the token itself: GoTrue writes each sign-in method into the access token's `amr` claim
--    with the moment it was used, and a refresh carries those moments over unchanged, so refreshing
--    an old session does not make it recent. Clients that are refused (NL004) ask for a new emailed
--    code for the same account, which starts a fresh session, and retry.
--
-- 2. Account deletion could skip the Worker (AUD-006)
--
--    `request_account_deletion()` was granted to `authenticated`, so it could be called straight
--    through PostgREST, past the Worker's refusal to start a deletion it could not finish. It is no
--    longer callable by users at all. The Worker verifies the caller's token and its sign-in time,
--    then calls `request_account_deletion_for(owner, signed_in_at)` with the service role, and that
--    function checks the time again.
--
-- Rollout: apply this migration, then deploy the Worker that ships with it, then the clients.
--   * Between the migration and the Worker, account deletion answers 403 and deletes nothing: the
--     deployed Worker still calls the user-token function, which is no longer granted to users.
--   * A Worker deployed first answers 503 (its RPC does not exist yet), and also deletes nothing.
--   * Clients that predate NL004 show their generic "could not delete" message when refused, and
--     the user can sign out and in again and retry. Nothing is lost either way.
--
-- Rollback: GRANT EXECUTE ON FUNCTION public.request_account_deletion() TO authenticated, drop the
-- new functions, and restore delete_all_user_cloud_data from 20261103000000. No data changes.

/*
 * When this session last authenticated, from the access token's `amr` claim, or null.
 *
 * `amr` is a list of { method, timestamp } with the timestamp in seconds. Anything not shaped like
 * that is ignored rather than trusted: a token with no usable entry has no recent sign-in.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.session_signed_in_at()
RETURNS timestamptz
LANGUAGE sql
STABLE
SET search_path = public
AS $$
    SELECT to_timestamp(max((entry->>'timestamp')::double precision))
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(auth.jwt()->'amr') = 'array' THEN auth.jwt()->'amr' ELSE '[]'::jsonb END
    ) AS entry
    WHERE jsonb_typeof(entry) = 'object'
      AND (entry->>'timestamp') ~ '^[0-9]{1,12}(\.[0-9]+)?$';
$$;

/*
 * Refuses unless [p_signed_in_at] is within the last ten minutes. NL004 is a definite answer that the
 * user has to confirm it is them; clients prompt for a code on it and never treat it as a failure to
 * reach the server. Five minutes of clock skew are allowed into the future.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.assert_signed_in_recently(p_signed_in_at timestamptz)
RETURNS void
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    IF p_signed_in_at IS NULL
       OR p_signed_in_at < timezone('utc', now()) - interval '10 minutes'
       OR p_signed_in_at > timezone('utc', now()) + interval '5 minutes'
    THEN
        RAISE EXCEPTION
            'reauthentication_required: confirm it is you with a new sign-in code, then try again.'
            USING ERRCODE = 'NL004';
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION notelikeus_internal.session_signed_in_at() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.assert_signed_in_recently(timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION notelikeus_internal.session_signed_in_at() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.assert_signed_in_recently(timestamptz) TO authenticated, service_role;

-- ---------------------------------------------------------------------------------------------
-- Cloud wipe: unchanged from 20261103000000 apart from the recent-sign-in check.
-- ---------------------------------------------------------------------------------------------

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

    -- AUD-005: a live token alone is not enough to erase the account's cloud data.
    PERFORM notelikeus_internal.assert_signed_in_recently(notelikeus_internal.session_signed_in_at());

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

-- ---------------------------------------------------------------------------------------------
-- Account deletion: started only by the Worker, for the owner it verified.
-- ---------------------------------------------------------------------------------------------

-- AUD-006. The function stays, so a rollback is a single GRANT, but no user can call it.
REVOKE EXECUTE ON FUNCTION public.request_account_deletion() FROM authenticated;

/*
 * Step 1 of 20261102010000's protocol, for [p_owner_id], whose token the Worker has verified and
 * whose session signed in at [p_signed_in_at].
 *
 * The body is request_account_deletion()'s with the owner passed in rather than read from the token,
 * since the service role has no `auth.uid()`. The sign-in time is checked here too, so a Worker bug
 * that skipped its own check still could not delete an account on a stale session.
 */
CREATE OR REPLACE FUNCTION public.request_account_deletion_for(
    p_owner_id UUID,
    p_signed_in_at TIMESTAMPTZ
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := p_owner_id;
    v_notes INTEGER := 0;
    v_attachments INTEGER := 0;
    v_generation BIGINT;
BEGIN
    PERFORM public.require_service_role();
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'owner is required' USING ERRCODE = '22023';
    END IF;
    PERFORM notelikeus_internal.assert_signed_in_recently(p_signed_in_at);

    PERFORM pg_advisory_xact_lock(hashtextextended('note_sync_write:' || v_owner::text, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('note_attachment_quota:' || v_owner::text, 0));

    PERFORM notelikeus_internal.begin_sync_mutation();

    INSERT INTO public.account_deletion_requests (owner_id)
    VALUES (v_owner)
    ON CONFLICT (owner_id) DO NOTHING;

    -- The wipe-generation guard refuses a delete that cannot show it knows the current generation.
    SELECT s.wipe_generation INTO v_generation
    FROM public.account_sync_state s
    WHERE s.owner_id = v_owner;
    PERFORM public.present_wipe_generation(COALESCE(v_generation, 0));

    DELETE FROM public.note_attachments WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_attachments = ROW_COUNT;

    DELETE FROM public.notes WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    DELETE FROM public.note_tombstones WHERE owner_id = v_owner;
    DELETE FROM public.sync_meta WHERE owner_id = v_owner;
    DELETE FROM public.account_sync_state WHERE owner_id = v_owner;

    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object(
        'status', 'accepted',
        'notes_deleted', v_notes,
        'attachments_deleted', v_attachments
    );
END;
$$;

REVOKE ALL ON FUNCTION public.request_account_deletion_for(UUID, TIMESTAMPTZ) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.request_account_deletion_for(UUID, TIMESTAMPTZ) TO service_role;
