-- Regression tests for 20250904010000_verified_firebase_uid_link.sql.
--
-- Before it, `firebase_uid` was the primary key, so the first account to claim a uid held it
-- forever: any account could claim any uid and permanently lock its real owner out of migrating.
-- The `EXISTS` check inside `link_firebase_uid` never caught that — it runs SECURITY INVOKER under
-- RLS and so cannot see another owner's row — the unique violation did. Now a claim and a proof are
-- different things, and only a proof is exclusive.

begin;
select plan(16);

select tests.create_supabase_user('verify_a@notelikeus.test');
select tests.create_supabase_user('verify_b@notelikeus.test');
select tests.create_supabase_user('verify_c@notelikeus.test');

-- ---------------------------------------------------------------------------
-- An unproven claim is not exclusive: squatting no longer locks anyone out.
-- ---------------------------------------------------------------------------
select tests.authenticate_as('verify_b@notelikeus.test');
select is(
  (public.link_firebase_uid('victim_uid')->>'verified')::boolean,
  false,
  'a client claim is recorded as unverified'
);

select tests.authenticate_as('verify_a@notelikeus.test');
select is(
  public.link_firebase_uid('victim_uid')->>'firebase_uid',
  'victim_uid',
  'the real owner can still claim a uid another account squatted'
);

-- ---------------------------------------------------------------------------
-- Only service_role can record a proof, and only it can see the verified flag change.
-- ---------------------------------------------------------------------------
select throws_ok(
  $$ select public.link_verified_firebase_uid('victim_uid', tests.get_supabase_uid('verify_a@notelikeus.test')) $$,
  '42501',
  null,
  'an authenticated client cannot record its own proof'
);
select throws_ok(
  $$ update public.firebase_uid_mappings set verified = true $$,
  '42501',
  null,
  'an authenticated client cannot forge the verified flag directly'
);

select tests.clear_authentication();
select throws_ok(
  $$ select public.link_verified_firebase_uid('victim_uid', gen_random_uuid()) $$,
  '42501',
  null,
  'anonymous cannot record a proof'
);
select throws_ok(
  $$ select public.get_firebase_uid_link() $$,
  '42501',
  null,
  'anonymous cannot read a uid link'
);

-- ---------------------------------------------------------------------------
-- A proof displaces assertions and becomes exclusive.
-- ---------------------------------------------------------------------------
set local role service_role;
select is(
  (
    public.link_verified_firebase_uid(
      'victim_uid',
      tests.get_supabase_uid('verify_a@notelikeus.test')
    )->>'displaced_unverified_claims'
  )::int,
  1,
  'a proof displaces another account''s unproven claim on the same uid'
);
select throws_ok(
  $$ select public.link_verified_firebase_uid('victim_uid', tests.get_supabase_uid('verify_b@notelikeus.test')) $$,
  '23505',
  null,
  'two accounts cannot both prove one Firebase uid'
);
reset role;

select tests.authenticate_as('verify_b@notelikeus.test');
select throws_ok(
  $$ select public.link_firebase_uid('victim_uid') $$,
  '23505',
  null,
  'a proven uid can no longer be claimed by anyone else'
);
select is(
  public.firebase_uid_proven_elsewhere('victim_uid', tests.get_supabase_uid('verify_b@notelikeus.test')),
  true,
  'the exclusivity check sees rows RLS hides from the caller'
);
select is(
  public.firebase_uid_proven_elsewhere('never_claimed_uid', tests.get_supabase_uid('verify_b@notelikeus.test')),
  false,
  'the exclusivity check reports nothing about uids that are merely asserted'
);
select is(
  (select count(*)::int from public.firebase_uid_mappings),
  0,
  'RLS still hides every other account''s mapping row'
);

-- ---------------------------------------------------------------------------
-- Provenance survives a re-assertion of the same uid, and only that.
-- ---------------------------------------------------------------------------
select tests.authenticate_as('verify_a@notelikeus.test');
-- Each link and the assertion that reads it back must be separate statements: the getter is
-- STABLE, so inside one statement it sees the snapshot from before the write.
select lives_ok(
  $$ select public.link_firebase_uid('victim_uid') $$,
  'the proven owner can re-assert their own uid'
);
select is(
  (public.get_firebase_uid_link()->>'verified')::boolean,
  true,
  're-asserting the same uid keeps an existing proof'
);
select lives_ok(
  $$ select public.link_firebase_uid('a_different_uid') $$,
  'the owner can point their mapping at a different uid'
);
select is(
  (public.get_firebase_uid_link()->>'verified')::boolean,
  false,
  'naming a different uid drops the proof, because that uid is unproven'
);

select * from finish();
rollback;
