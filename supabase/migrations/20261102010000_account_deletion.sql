-- In-app account deletion: immediate, and finished by the Worker's sweep if a request dies part-way.
--
-- Why rows are purged before the auth user goes
-- ---------------------------------------------
-- Every owner table cascades from `auth.users`, but `notes`, `note_tombstones`, `note_attachments`
-- and `account_sync_state` carry `sync_mutation_guard`, and two of them the wipe-generation guard.
-- Those triggers fire for a cascade too, and the Auth admin API cannot open them, so deleting a user
-- who still has a row in any of them fails. The transaction below removes the rows under the guards,
-- and the fence keeps them from coming back before the auth user is deleted.
--
-- The protocol
-- ------------
--   1. `request_account_deletion()`, as the user: records the request and deletes every row of the
--      account in one transaction. From this commit the account's notes are gone and can never come
--      back: the fence refuses writes and reads with SQLSTATE NL003.
--   2. The Worker deletes every object under `owners/<id>/` in R2. It lists the prefix rather than
--      rows, which also reaches bytes no row references (an upload whose finalization failed
--      ambiguously, F-08).
--   3. The Worker deletes the auth user through the admin API, only once the prefix lists empty, and
--      records it with `mark_account_auth_user_deleted`.
--   4. The cron sweep retries 2 and 3 for any request left unfinished. It also lists the prefix again
--      for a request whose user is gone, because an upload already in flight at step 1 can land bytes
--      after the first listing, and `complete_account_deletion` then forgets the request.
--
-- `account_deletion_requests.owner_id` has no foreign key on purpose: the row has to outlive the auth
-- user so the sweep can finish, and until it is forgotten it holds only that id and two timestamps.
--
-- Why the request outlives the user by two hours: an access token stays valid until it expires (one
-- hour, `jwt_expiry` in supabase/config.toml), and the fence is how a signed-in device holding one
-- learns the account was deleted. Without the row its next pull would read as an empty account.
--
-- Rollout: apply this migration, deploy the Worker with SUPABASE_SERVICE_ROLE_KEY set, then the
-- clients. The Worker refuses before step 1 if the key is missing. Clients that predate NL003 fail
-- their syncs visibly once an account is deleted, which is the outcome they already have for a
-- session that has ended.
--
-- Rollback: drop the triggers, the functions and the table, then restore fetch_full_snapshot and
-- pull_changes from 20261018000000 and get_sync_generation likewise. Deleted accounts stay deleted.

CREATE TABLE public.account_deletion_requests (
    owner_id UUID PRIMARY KEY,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    auth_user_deleted_at TIMESTAMPTZ
);

COMMENT ON TABLE public.account_deletion_requests IS
    'Accounts being deleted. Deliberately no foreign key to auth.users: a row outlives the user until '
    'the Worker sweep has confirmed no attachment object is left, and is then removed.';

CREATE INDEX account_deletion_requests_requested_idx
    ON public.account_deletion_requests (requested_at);

ALTER TABLE public.account_deletion_requests ENABLE ROW LEVEL SECURITY;

-- The fence and the request RPC run as the user, so the user has to be able to read and create their
-- own row. Writes still need the mutation guard below, so PostgREST cannot start a deletion that
-- skips the purge, and there is no UPDATE or DELETE policy at all.
CREATE POLICY account_deletion_requests_select_own ON public.account_deletion_requests
    FOR SELECT TO authenticated
    USING (owner_id = (SELECT auth.uid()));

CREATE POLICY account_deletion_requests_insert_own ON public.account_deletion_requests
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = (SELECT auth.uid()));

CREATE TRIGGER account_deletion_requests_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.account_deletion_requests
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.sync_mutation_guard();

-- `account_sync_state` had no way to lose a row, because a wipe keeps it.
CREATE POLICY account_sync_state_delete_own ON public.account_sync_state
    FOR DELETE TO authenticated
    USING (owner_id = (SELECT auth.uid()));

-- ---------------------------------------------------------------------------------------------
-- The fence
-- ---------------------------------------------------------------------------------------------

/*
 * Refuses anything that would treat a deleted account as live.
 *
 * NL003 is a definite answer that this account is being deleted, and it is what lets a client erase
 * its local copy: a client must never do that on an ambiguous failure.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.assert_account_not_deleted(p_owner UUID)
RETURNS void
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.account_deletion_requests r WHERE r.owner_id = p_owner) THEN
        RAISE EXCEPTION 'account_deleted: this Notelikeus account was deleted.'
            USING ERRCODE = 'NL003';
    END IF;
END;
$$;

/*
 * INSERT and UPDATE only: the purge itself deletes, and so does the sweep. Named so it fires before
 * the wipe-generation guard (triggers run alphabetically), because "this account is gone" is the
 * answer a device needs, not "re-sync".
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.account_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    PERFORM notelikeus_internal.assert_account_not_deleted(NEW.owner_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER notes_account_deletion_fence
    BEFORE INSERT OR UPDATE ON public.notes
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.account_deletion_fence();

CREATE TRIGGER note_tombstones_account_deletion_fence
    BEFORE INSERT OR UPDATE ON public.note_tombstones
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.account_deletion_fence();

CREATE TRIGGER note_attachments_account_deletion_fence
    BEFORE INSERT OR UPDATE ON public.note_attachments
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.account_deletion_fence();

-- A wipe from another device would otherwise recreate this row, and its mutation guard would then
-- make the auth user impossible to delete.
CREATE TRIGGER account_sync_state_account_deletion_fence
    BEFORE INSERT OR UPDATE ON public.account_sync_state
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.account_deletion_fence();

REVOKE ALL ON FUNCTION notelikeus_internal.assert_account_not_deleted(UUID) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.account_deletion_fence() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION notelikeus_internal.assert_account_not_deleted(UUID) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.account_deletion_fence() TO authenticated, service_role;

-- ---------------------------------------------------------------------------------------------
-- Step 1: the request, as the user
-- ---------------------------------------------------------------------------------------------

/*
 * Deletes every row of the caller's account and records that the account is going.
 *
 * Takes the same per-owner locks as the writers, so a write in progress finishes first and its row is
 * deleted here, and a write that was waiting finds the request and is refused. Idempotent: a retry
 * after a lost response deletes nothing further and answers the same way.
 */
CREATE OR REPLACE FUNCTION public.request_account_deletion()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_notes INTEGER := 0;
    v_attachments INTEGER := 0;
    v_generation BIGINT;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

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

REVOKE ALL ON FUNCTION public.request_account_deletion() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.request_account_deletion() TO authenticated;

-- ---------------------------------------------------------------------------------------------
-- Steps 3 and 4: the Worker's service-role bookkeeping
-- ---------------------------------------------------------------------------------------------

/*
 * Requests with work left, oldest first. `settled` says the user has been gone long enough that any
 * upload and any access token from before the deletion has run out.
 */
CREATE OR REPLACE FUNCTION public.list_pending_account_deletions(p_limit INTEGER DEFAULT 25)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 25), 100));
BEGIN
    PERFORM public.require_service_role();
    RETURN coalesce((
        SELECT jsonb_agg(jsonb_build_object(
            'owner_id', r.owner_id,
            'auth_user_deleted', r.auth_user_deleted_at IS NOT NULL,
            'settled', r.auth_user_deleted_at IS NOT NULL
                AND r.auth_user_deleted_at < timezone('utc', now()) - interval '2 hours'
        ))
        FROM (
            SELECT * FROM public.account_deletion_requests
            ORDER BY requested_at ASC
            LIMIT v_limit
        ) r
    ), '[]'::jsonb);
END;
$$;

CREATE OR REPLACE FUNCTION public.mark_account_auth_user_deleted(p_owner_id UUID)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_updated INTEGER := 0;
BEGIN
    PERFORM public.require_service_role();
    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.account_deletion_requests
    SET auth_user_deleted_at = COALESCE(auth_user_deleted_at, timezone('utc', now()))
    WHERE owner_id = p_owner_id;
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    IF v_updated = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_requested');
    END IF;
    RETURN jsonb_build_object('status', 'applied');
END;
$$;

/*
 * Forgets a finished request. Re-checks the rule itself, so no caller can remove the fence while a
 * user or an access token is still live.
 */
CREATE OR REPLACE FUNCTION public.complete_account_deletion(p_owner_id UUID)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_deleted INTEGER := 0;
BEGIN
    PERFORM public.require_service_role();
    PERFORM notelikeus_internal.begin_sync_mutation();
    DELETE FROM public.account_deletion_requests
    WHERE owner_id = p_owner_id
      AND auth_user_deleted_at IS NOT NULL
      AND auth_user_deleted_at < timezone('utc', now()) - interval '2 hours';
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    IF v_deleted = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_eligible');
    END IF;
    RETURN jsonb_build_object('status', 'applied');
END;
$$;

REVOKE ALL ON FUNCTION public.list_pending_account_deletions(INTEGER) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.mark_account_auth_user_deleted(UUID) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.complete_account_deletion(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.list_pending_account_deletions(INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.mark_account_auth_user_deleted(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION public.complete_account_deletion(UUID) TO service_role;

-- ---------------------------------------------------------------------------------------------
-- Read paths: a signed-in device learns the account is gone from its next pull
-- ---------------------------------------------------------------------------------------------

-- Same results as 20261018000000, plus the fence. An empty answer here is what a client would
-- otherwise see, and it cannot tell that from a truncated read.
CREATE OR REPLACE FUNCTION public.get_sync_generation()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    PERFORM notelikeus_internal.assert_account_not_deleted(v_owner);
    RETURN jsonb_build_object(
        'wipe_generation',
        COALESCE((
            SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = v_owner
        ), 0)
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.fetch_full_snapshot()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    PERFORM notelikeus_internal.assert_account_not_deleted(auth.uid());
    RETURN jsonb_build_object(
        'notes',
        COALESCE(
            (
                SELECT jsonb_agg(public.note_row_to_json(n.*) ORDER BY n.position, n.local_id)
                FROM public.notes n
                WHERE n.owner_id = auth.uid()
            ),
            '[]'::jsonb
        ),
        'tombstones',
        COALESCE(
            (
                SELECT jsonb_agg(public.tombstone_row_to_json(t.*) ORDER BY t.revision)
                FROM public.note_tombstones t
                WHERE t.owner_id = auth.uid()
            ),
            '[]'::jsonb
        ),
        'note_count',
        (SELECT count(*)::integer FROM public.notes n WHERE n.owner_id = auth.uid()),
        'wipe_generation',
        COALESCE((
            SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = auth.uid()
        ), 0)
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.pull_changes(
    p_after_revision BIGINT DEFAULT 0,
    p_limit INTEGER DEFAULT 100
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    PERFORM notelikeus_internal.assert_account_not_deleted(auth.uid());
    RETURN (
        WITH combined AS (
            SELECT revision, public.note_row_to_json(n.*) AS change
            FROM public.notes n
            WHERE n.owner_id = auth.uid() AND n.revision > COALESCE(p_after_revision, 0)
            UNION ALL
            SELECT revision, public.tombstone_row_to_json(t.*) AS change
            FROM public.note_tombstones t
            WHERE t.owner_id = auth.uid() AND t.revision > COALESCE(p_after_revision, 0)
        ),
        limited AS (
            SELECT change, revision
            FROM combined
            ORDER BY revision ASC
            LIMIT GREATEST(p_limit, 1)
        ),
        totals AS (
            SELECT count(*) AS total FROM combined
        )
        SELECT jsonb_build_object(
            'changes',
            COALESCE((SELECT jsonb_agg(change ORDER BY revision ASC) FROM limited), '[]'::jsonb),
            'has_more',
            (SELECT total > p_limit FROM totals),
            'wipe_generation',
            COALESCE((
                SELECT s.wipe_generation FROM public.account_sync_state s WHERE s.owner_id = auth.uid()
            ), 0)
        )
    );
END;
$$;
