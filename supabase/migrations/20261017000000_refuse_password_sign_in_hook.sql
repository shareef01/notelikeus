-- Refuse password sign-in on the hosted project (audit S-01).
--
-- Every client signs in with an emailed one-time code (D29), and the password form exists only in
-- debug builds and the e2e stack. GoTrue still serves `grant_type=password` to anyone holding the
-- public anon key, so someone could sign up with a password for another person's address before
-- that person ever signs in by code; once the owner confirms the address with a code, the
-- stranger's password would still open the account.
--
-- Supabase calls this function as its Custom Access Token hook before it issues any access token.
-- A token requested by password is refused with 403; every other method (`otp`, `magiclink`,
-- `email/signup`, `token_refresh`, ...) gets its claims back unchanged. The Password Verification
-- hook would be the narrower tool, but it needs the Teams plan; this hook is on every plan.
--
-- The function only takes effect where the hook is switched on: the hosted project, through
-- .github/workflows/supabase-auth.yml. supabase/config.toml leaves it off, because local
-- development and the e2e suite sign in with a password.
--
-- A fault here would refuse every sign-in, so it does as little as possible: no table reads, no
-- claim changes, and a plain pass-through for anything that is not exactly 'password'.

CREATE OR REPLACE FUNCTION public.refuse_password_sign_in(event jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SET search_path = ''
AS $$
BEGIN
    IF event->>'authentication_method' = 'password' THEN
        RETURN jsonb_build_object(
            'error', jsonb_build_object(
                'http_code', 403,
                'message', 'Password sign-in is turned off. Sign in with an emailed code instead.'
            )
        );
    END IF;
    RETURN jsonb_build_object('claims', event->'claims');
END;
$$;

-- Only Supabase Auth may call it; it must not appear as a PostgREST RPC.
REVOKE EXECUTE ON FUNCTION public.refuse_password_sign_in(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.refuse_password_sign_in(jsonb) TO supabase_auth_admin;
