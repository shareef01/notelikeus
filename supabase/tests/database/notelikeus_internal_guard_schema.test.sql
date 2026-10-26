-- FU-4: the sync-mutation guard helpers must not be reachable through the Data API.
--
-- `begin_sync_mutation()` and `end_sync_mutation()` were in `public` — an exposed schema — and
-- granted to `authenticated`, so PostgREST published them as RPCs. They moved to
-- `notelikeus_internal`, which supabase/config.toml does not list in `[api] schemas`, and
-- `sync_mutation_guard()` moved with them.
--
-- The functions themselves are unchanged; what this file pins is the surface. The behavioural
-- guarantee (a direct table write is refused unless a mutation opened the guard) is covered by the
-- suites that write through the RPCs.

begin;
select plan(15);

-- ------------------------------------------------ gone from the exposed schema, present in the new one

select hasnt_function('public', 'begin_sync_mutation', 'begin_sync_mutation is not in public');
select hasnt_function('public', 'end_sync_mutation', 'end_sync_mutation is not in public');
select hasnt_function('public', 'sync_mutation_guard', 'sync_mutation_guard is not in public');

select has_schema('notelikeus_internal', 'the internal schema exists');
select has_function(
  'notelikeus_internal', 'begin_sync_mutation', 'begin_sync_mutation lives in the internal schema'
);
select has_function(
  'notelikeus_internal', 'end_sync_mutation', 'end_sync_mutation lives in the internal schema'
);
select has_function(
  'notelikeus_internal', 'sync_mutation_guard', 'sync_mutation_guard lives in the internal schema'
);

-- ------------------------------------------------ exactly the roles that need it can execute it

-- The mutation RPCs are SECURITY INVOKER, so the calling role must hold EXECUTE, and the guard
-- trigger fires as whoever wrote the row. (This pgTAP build exposes only the 3-argument
-- `has_function_privilege`, so each assertion is wrapped in `ok()` to keep its description.)
select ok(
  has_function_privilege('authenticated', 'notelikeus_internal.begin_sync_mutation()', 'EXECUTE'),
  'authenticated can still open the guard, which its own RPCs require'
);
select ok(
  has_function_privilege('authenticated', 'notelikeus_internal.end_sync_mutation()', 'EXECUTE'),
  'authenticated can still close the guard'
);
select ok(
  has_function_privilege('authenticated', 'notelikeus_internal.sync_mutation_guard()', 'EXECUTE'),
  'the guard trigger can still run for an authenticated write'
);
-- The sweep functions run as service_role, so they need the same access.
select ok(
  has_function_privilege('service_role', 'notelikeus_internal.begin_sync_mutation()', 'EXECUTE'),
  'service_role can still open the guard for the sweeps'
);
select ok(
  NOT has_function_privilege('anon', 'notelikeus_internal.begin_sync_mutation()', 'EXECUTE'),
  'anon cannot open the guard'
);
select ok(
  NOT has_function_privilege('anon', 'notelikeus_internal.sync_mutation_guard()', 'EXECUTE'),
  'anon cannot run the guard'
);
select ok(
  NOT has_function_privilege('anon', 'notelikeus_internal.end_sync_mutation()', 'EXECUTE'),
  'anon cannot close the guard'
);

-- ------------------------------------------------ the gate still works, through the new schema

select tests.create_supabase_user('guard_a@notelikeus.test');
select tests.authenticate_as('guard_a@notelikeus.test');

select throws_ok(
  $$ insert into public.notes (
       note_id, local_id, owner_id, revision, title, content, client_timestamp, color, position
     ) values (
       '900', 900, auth.uid(), 1, 'unopened', '', 1, 1, 0
     ) $$,
  '42501',
  null,
  'a direct write is still refused while the guard is closed'
);

select * from finish();
rollback;
