-- refuse_password_sign_in refuses a token requested by password and passes every other one through
-- with its claims untouched (audit S-01). A fault here refuses every sign-in on the hosted project.

begin;
select plan(8);

select is(
  public.refuse_password_sign_in(jsonb_build_object(
    'user_id', '00000000-0000-0000-0000-000000000001',
    'claims', jsonb_build_object('sub', '00000000-0000-0000-0000-000000000001', 'role', 'authenticated'),
    'authentication_method', 'password'
  )) #>> '{error,http_code}',
  '403',
  'a password sign-in is refused with 403'
);

select is(
  public.refuse_password_sign_in(jsonb_build_object(
    'claims', jsonb_build_object('role', 'authenticated'),
    'authentication_method', 'password'
  )) ? 'claims',
  false,
  'a refused password sign-in carries no claims'
);

select is(
  public.refuse_password_sign_in(jsonb_build_object(
    'claims', jsonb_build_object('sub', 'u1', 'role', 'authenticated', 'email', 'a@notelikeus.test'),
    'authentication_method', method
  )),
  jsonb_build_object('claims', jsonb_build_object('sub', 'u1', 'role', 'authenticated', 'email', 'a@notelikeus.test')),
  format('a %s sign-in keeps its claims unchanged', method)
) from unnest(array['otp', 'magiclink', 'email/signup', 'token_refresh']) as method;

select is(
  public.refuse_password_sign_in(jsonb_build_object('claims', jsonb_build_object('role', 'authenticated'))) ? 'error',
  false,
  'a request with no authentication_method is not refused'
);

select is(
  has_function_privilege('authenticated', 'public.refuse_password_sign_in(jsonb)', 'EXECUTE')
    or has_function_privilege('anon', 'public.refuse_password_sign_in(jsonb)', 'EXECUTE'),
  false,
  'neither anon nor authenticated can call the hook as an RPC'
);

select * from finish();
rollback;
