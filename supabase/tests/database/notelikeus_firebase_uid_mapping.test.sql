begin;
select plan(5);

select tests.create_supabase_user('user_a@notelikeus.test');
select tests.create_supabase_user('user_b@notelikeus.test');

select tests.authenticate_as('user_a@notelikeus.test');
select results_eq(
  $$ select (public.link_firebase_uid('firebase_uid_a')->>'firebase_uid') $$,
  ARRAY['firebase_uid_a'::text],
  'user A can link a firebase uid'
);
select results_eq(
  $$ select public.get_linked_firebase_uid() $$,
  ARRAY['firebase_uid_a'::text],
  'user A can read linked firebase uid'
);

select tests.authenticate_as('user_b@notelikeus.test');
select throws_ok(
  $$ select public.link_firebase_uid('firebase_uid_a') $$,
  '23505',
  null,
  'user B cannot steal user A firebase uid'
);

select tests.authenticate_as('user_a@notelikeus.test');
select results_eq(
  $$ select (public.link_firebase_uid('firebase_uid_a_relinked')->>'firebase_uid') $$,
  ARRAY['firebase_uid_a_relinked'::text],
  'user A can relink their own mapping'
);

select tests.clear_authentication();
select throws_ok(
  $$ select public.link_firebase_uid('anon') $$,
  -- 42501, not 28000: since 20250904000000 anon has no EXECUTE grant, so PostgREST is
  -- refused before the function body's own 'not authenticated' check can run.
  '42501',
  null,
  'anonymous cannot link firebase uid'
);

select * from finish();
rollback;
