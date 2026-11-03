begin;
select plan(17);

-- AUD-005: erasing an account's cloud data, or the account, needs a session that signed in within the
-- last ten minutes, read from the access token's `amr` claim. AUD-006: account deletion starts only
-- through the service role. Whether GoTrue keeps `amr` across a refresh is a property of GoTrue, not
-- of these functions; scripts/ops/account-deletion-e2e.mjs checks it against a real one.

create function tests.as_service_role()
returns void
language plpgsql
as $$
begin
  perform set_config('role', 'service_role', true);
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
end;
$$;

-- Claims exactly as given, for tokens GoTrue would not issue but a caller could still present.
create function tests.authenticate_with_claims(identifier text, extra jsonb)
returns void
language plpgsql
as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config(
    'request.jwt.claims',
    (jsonb_build_object('sub', tests.get_supabase_uid(identifier)) || extra)::text,
    true
  );
end;
$$;

select tests.create_supabase_user('recent_a@notelikeus.test');
select tests.create_supabase_user('recent_b@notelikeus.test');

select tests.authenticate_as('recent_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint, 'Kept', '', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

-- ---------------------------------------------------------------------------- the cloud wipe

select tests.authenticate_as_signed_in_at('recent_a@notelikeus.test', now() - interval '11 minutes');
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  'NL004', null, 'a session that signed in eleven minutes ago cannot wipe the account'
);
select is(
  (select count(*)::int from public.notes), 1, 'and the refused wipe deleted nothing'
);

select tests.authenticate_with_claims('recent_a@notelikeus.test', '{}'::jsonb);
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  'NL004', null, 'a token with no amr claim cannot wipe'
);

select tests.authenticate_with_claims('recent_a@notelikeus.test', '{"amr": ["pwd"]}'::jsonb);
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  'NL004', null, 'an amr claim without timestamps proves nothing'
);

select tests.authenticate_with_claims(
  'recent_a@notelikeus.test',
  jsonb_build_object('amr', jsonb_build_array(jsonb_build_object(
    'method', 'otp', 'timestamp', floor(extract(epoch from now() + interval '1 hour'))::bigint
  )))
);
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  'NL004', null, 'a sign-in time in the future is refused'
);

-- An old sign-in plus a recent one (a second method, as with MFA) is recent.
select tests.authenticate_with_claims(
  'recent_a@notelikeus.test',
  jsonb_build_object('amr', jsonb_build_array(
    jsonb_build_object('method', 'password', 'timestamp', floor(extract(epoch from now() - interval '2 days'))::bigint),
    jsonb_build_object('method', 'otp', 'timestamp', floor(extract(epoch from now() - interval '1 minute'))::bigint)
  ))
);
select ok(
  notelikeus_internal.session_signed_in_at() > now() - interval '2 minutes',
  'the most recent sign-in method decides'
);

select tests.authenticate_as_signed_in_at('recent_a@notelikeus.test', now() - interval '9 minutes');
select is(
  public.delete_all_user_cloud_data()->>'status', 'applied', 'a session nine minutes old can wipe'
);
select is((select count(*)::int from public.notes), 0, 'and the wipe went through');

-- ------------------------------------------------------------------------- account deletion

select tests.authenticate_as('recent_b@notelikeus.test');
select public.apply_note_change(
  '2', 2::bigint, null::bigint, 'B', '', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select throws_ok(
  $$ select public.request_account_deletion() $$,
  '42501', null, 'even a fresh session cannot start a deletion without the Worker'
);
select throws_ok(
  format(
    'select public.request_account_deletion_for(%L, now())',
    tests.get_supabase_uid('recent_b@notelikeus.test')
  ),
  '42501', null, 'nor call the service-role deletion for itself'
);

select tests.clear_authentication();
select throws_ok(
  format(
    'select public.request_account_deletion_for(%L, now())',
    tests.get_supabase_uid('recent_b@notelikeus.test')
  ),
  '42501', null, 'anon cannot start a deletion'
);

select tests.as_service_role();
select throws_ok(
  format(
    'select public.request_account_deletion_for(%L, now() - interval ''11 minutes'')',
    tests.get_supabase_uid('recent_b@notelikeus.test')
  ),
  'NL004', null, 'the service role still refuses a stale sign-in'
);
select throws_ok(
  format(
    'select public.request_account_deletion_for(%L, null)',
    tests.get_supabase_uid('recent_b@notelikeus.test')
  ),
  'NL004', null, 'and a missing one'
);
select throws_ok(
  $$ select public.request_account_deletion_for(null, now()) $$,
  '22023', null, 'and a missing owner'
);
select is(
  (select count(*)::int from public.account_deletion_requests
   where owner_id = tests.get_supabase_uid('recent_b@notelikeus.test')),
  0,
  'no refused call recorded a request'
);
select is(
  public.request_account_deletion_for(
    tests.get_supabase_uid('recent_b@notelikeus.test'), now() - interval '2 minutes'
  )->>'status',
  'accepted',
  'a recent sign-in starts the deletion'
);
select is(
  (select count(*)::int from public.notes
   where owner_id = tests.get_supabase_uid('recent_b@notelikeus.test')),
  0,
  'and purges that account'
);

select * from finish();
rollback;
