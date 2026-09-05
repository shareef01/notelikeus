-- Firebase uid ownership: separate a *claim* from *proof*.
--
-- `link_firebase_uid` accepts whatever uid the client sends. Nothing about an authenticated
-- Supabase session says anything about who owns a legacy Firebase identity, so every mapping
-- written that way is an assertion, not evidence. Two consequences followed from treating it as
-- evidence:
--
--   * `firebase_uid` was the primary key, so the first account to claim a uid held it forever.
--     Any account could claim any uid and permanently lock its real owner out of linking — a
--     durable denial-of-migration against a named victim, with no way back.
--   * A client reading the mapping back could not tell a proven link from an asserted one, so it
--     had to trust both equally.
--
-- The mapping confers no server-side authority — every RLS policy scopes on `auth.uid()`, and the
-- R2 Worker derives object keys from the bearer token — so an unproven claim is not a disclosure
-- risk. It only needs to stop being *exclusive*, and it needs to be distinguishable from a proven
-- one. This migration does both:
--
--   * `verified` records provenance. Unverified claims are no longer exclusive: several accounts
--     may assert the same uid, and none of them blocks the others.
--   * Only a *verified* claim is exclusive, and a verified claim displaces unverified assertions
--     of the same uid — the proven owner reclaims their identity from a squatter.
--   * `link_verified_firebase_uid` is the only way to set `verified`, and only `service_role` may
--     call it. It is reached from the attachments Worker, which checks the RS256 signature of a
--     Firebase ID token against Google's published keys before calling. See
--     `workers/attachments/src/firebaseIdToken.ts`.
--
-- Firebase remains the production backend; this changes no client default.

ALTER TABLE public.firebase_uid_mappings
    ADD COLUMN verified BOOLEAN NOT NULL DEFAULT FALSE;

-- One mapping per Supabase account (unchanged), but a uid is only spoken for once it is proven.
ALTER TABLE public.firebase_uid_mappings
    DROP CONSTRAINT firebase_uid_mappings_pkey;
ALTER TABLE public.firebase_uid_mappings
    DROP CONSTRAINT firebase_uid_mappings_owner_unique;
ALTER TABLE public.firebase_uid_mappings
    ADD CONSTRAINT firebase_uid_mappings_pkey PRIMARY KEY (owner_id);

CREATE UNIQUE INDEX firebase_uid_mappings_verified_uid_idx
    ON public.firebase_uid_mappings (firebase_uid)
    WHERE verified;

CREATE INDEX firebase_uid_mappings_uid_idx
    ON public.firebase_uid_mappings (firebase_uid);

-- Every row that existed before this migration was written without proof.
COMMENT ON COLUMN public.firebase_uid_mappings.verified IS
    'True only when link_verified_firebase_uid wrote the row after a Firebase ID token was '
    'cryptographically verified. Rows written by link_firebase_uid are unproven client assertions.';

/**
 * Whether some *other* account has proven this Firebase uid.
 *
 * SECURITY DEFINER because the check has to see rows the caller cannot: `link_firebase_uid` runs
 * SECURITY INVOKER as `authenticated`, and `firebase_uid_mappings_select_own` scopes SELECT to the
 * caller's own row — so an `EXISTS` inside that RPC can never see another owner's mapping and the
 * exclusivity check silently passes. Before this migration the `firebase_uid` primary key masked
 * that: the unique violation, not the check, was what refused a stolen uid.
 *
 * It returns a boolean and nothing else. The only fact disclosed is "this uid is already proven by
 * someone", which is exactly what the caller's error message says anyway — no owner id, no
 * timestamp, and nothing at all about uids that are merely asserted.
 */
CREATE OR REPLACE FUNCTION public.firebase_uid_proven_elsewhere(
    p_firebase_uid TEXT,
    p_owner_id UUID
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.firebase_uid_mappings
        WHERE firebase_uid = trim(p_firebase_uid)
          AND owner_id IS DISTINCT FROM p_owner_id
          AND verified
    );
$$;

CREATE OR REPLACE FUNCTION public.link_firebase_uid(p_firebase_uid TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_uid TEXT := trim(p_firebase_uid);
BEGIN
    PERFORM public.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF v_uid IS NULL OR char_length(v_uid) = 0 THEN
        RAISE EXCEPTION 'firebase_uid required' USING ERRCODE = '22023';
    END IF;

    -- Only a *proven* claim by someone else blocks this one. An unproven claim by another account
    -- no longer locks this caller out; it is an assertion with no more standing than this one.
    IF public.firebase_uid_proven_elsewhere(v_uid, v_owner) THEN
        RAISE EXCEPTION 'firebase_uid already verified for another account' USING ERRCODE = '23505';
    END IF;

    INSERT INTO public.firebase_uid_mappings (firebase_uid, owner_id, verified)
    VALUES (v_uid, v_owner, FALSE)
    ON CONFLICT (owner_id) DO UPDATE
        SET firebase_uid = EXCLUDED.firebase_uid,
            -- Re-asserting the same uid keeps an existing proof; naming a different one drops it,
            -- because the new uid has not been proven.
            verified = public.firebase_uid_mappings.verified
                       AND public.firebase_uid_mappings.firebase_uid = EXCLUDED.firebase_uid,
            linked_at = timezone('utc', now());

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'firebase_uid', v_uid,
        'owner_id', v_owner::text,
        'verified', FALSE
    );
END;
$$;

/**
 * Records a Firebase uid link that has been cryptographically proven.
 *
 * `service_role` only: the caller must already have verified an RS256-signed Firebase ID token
 * whose `sub` is p_firebase_uid and whose `aud`/`iss` name the expected Firebase project. There is
 * no way to reach this from an `anon` or `authenticated` PostgREST session, so a client cannot
 * assert its own proof.
 *
 * The owner id is passed explicitly because `auth.uid()` is null for a service-role caller acting
 * on a user's behalf.
 */
CREATE OR REPLACE FUNCTION public.link_verified_firebase_uid(
    p_firebase_uid TEXT,
    p_owner_id UUID
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_uid TEXT := trim(p_firebase_uid);
    v_displaced INTEGER := 0;
BEGIN
    PERFORM public.begin_sync_mutation();

    IF p_owner_id IS NULL THEN
        RAISE EXCEPTION 'owner_id required' USING ERRCODE = '22023';
    END IF;
    IF v_uid IS NULL OR char_length(v_uid) = 0 THEN
        RAISE EXCEPTION 'firebase_uid required' USING ERRCODE = '22023';
    END IF;
    -- An unknown owner is rejected by the `owner_id` foreign key on insert (23503). Checking it
    -- here instead would need SELECT on `auth.users`, which `service_role` does not hold and
    -- should not be granted for this.

    -- A proof beats an assertion: unverified claims on this uid by other accounts are dropped, so
    -- a squatter cannot keep the real owner out. Another *verified* holder is a genuine conflict —
    -- two proven owners of one Firebase identity should not happen, and silently reassigning would
    -- hide it.
    IF public.firebase_uid_proven_elsewhere(v_uid, p_owner_id) THEN
        RAISE EXCEPTION 'firebase_uid already verified for another account' USING ERRCODE = '23505';
    END IF;

    DELETE FROM public.firebase_uid_mappings
    WHERE firebase_uid = v_uid AND owner_id <> p_owner_id AND NOT verified;
    GET DIAGNOSTICS v_displaced = ROW_COUNT;

    INSERT INTO public.firebase_uid_mappings (firebase_uid, owner_id, verified)
    VALUES (v_uid, p_owner_id, TRUE)
    ON CONFLICT (owner_id) DO UPDATE
        SET firebase_uid = EXCLUDED.firebase_uid,
            verified = TRUE,
            linked_at = timezone('utc', now());

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'firebase_uid', v_uid,
        'owner_id', p_owner_id::text,
        'verified', TRUE,
        'displaced_unverified_claims', v_displaced
    );
END;
$$;

/** The caller's own mapping, with its provenance, so a client can tell proof from assertion. */
CREATE OR REPLACE FUNCTION public.get_firebase_uid_link()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    SELECT coalesce(
        (
            SELECT jsonb_build_object('firebase_uid', firebase_uid, 'verified', verified)
            FROM public.firebase_uid_mappings
            WHERE owner_id = auth.uid()
        ),
        'null'::jsonb
    );
$$;

REVOKE ALL ON FUNCTION public.link_verified_firebase_uid(TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.link_verified_firebase_uid(TEXT, UUID) TO service_role;

REVOKE ALL ON FUNCTION public.firebase_uid_proven_elsewhere(TEXT, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.firebase_uid_proven_elsewhere(TEXT, UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_firebase_uid_link() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_firebase_uid_link() TO authenticated;

-- service_role reaches the guarded tables through this RPC, so it needs the same bracket helpers
-- the authenticated RPCs use.
GRANT EXECUTE ON FUNCTION public.begin_sync_mutation() TO service_role;
GRANT EXECUTE ON FUNCTION public.end_sync_mutation() TO service_role;
