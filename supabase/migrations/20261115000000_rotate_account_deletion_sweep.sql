ALTER TABLE public.account_deletion_requests
    ADD COLUMN last_attempted_at TIMESTAMPTZ;

CREATE INDEX account_deletion_requests_sweep_idx
    ON public.account_deletion_requests ((COALESCE(last_attempted_at, requested_at)), requested_at, owner_id);

-- Claim the least recently attempted requests. An abandoned batch becomes eligible again after
-- other requests get a turn, even if the Worker never reports an outcome.
CREATE OR REPLACE FUNCTION public.list_pending_account_deletions(p_limit INTEGER DEFAULT 25)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 25), 100));
    v_result jsonb;
BEGIN
    PERFORM public.require_service_role();
    PERFORM notelikeus_internal.begin_sync_mutation();
    WITH selected AS (
        SELECT owner_id
        FROM public.account_deletion_requests
        ORDER BY COALESCE(last_attempted_at, requested_at), requested_at, owner_id
        LIMIT v_limit
        FOR UPDATE SKIP LOCKED
    ), claimed AS (
        UPDATE public.account_deletion_requests AS r
        SET last_attempted_at = clock_timestamp()
        FROM selected
        WHERE r.owner_id = selected.owner_id
        RETURNING r.owner_id, r.auth_user_deleted_at
    )
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'owner_id', owner_id,
        'auth_user_deleted', auth_user_deleted_at IS NOT NULL,
        'settled', auth_user_deleted_at IS NOT NULL
            AND auth_user_deleted_at < timezone('utc', now()) - interval '2 hours'
    )), '[]'::jsonb)
    INTO v_result
    FROM claimed;
    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN v_result;
END;
$$;
