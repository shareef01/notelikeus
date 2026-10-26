-- Account wipe generation: a device that was offline across "delete cloud data" must not be able to
-- put the deleted notes back, and no write path may bypass that.
-- See supabase/migrations/20261018000000_account_wipe_generation.sql.

begin;
select plan(35);

select setval('public.sync_revision_seq', 20000, true);

select tests.create_supabase_user('wipe_a@notelikeus.test');
select tests.create_supabase_user('wipe_b@notelikeus.test');
select tests.authenticate_as('wipe_a@notelikeus.test');

-- ---------------------------------------------------------------- an account that was never wiped

select is(
  public.get_sync_generation()->>'wipe_generation',
  '0',
  'a fresh account reads as generation 0'
);

select is(
  public.fetch_full_snapshot()->>'wipe_generation',
  '0',
  'the snapshot publishes the generation'
);

select is(
  public.pull_changes(0::bigint, 100::integer)->>'wipe_generation',
  '0',
  'an incremental pull publishes the generation'
);

-- The whole point of the `generation 0` branch: a client older than this migration keeps working
-- on an account that has never been wiped.
select results_eq(
  $$ select (public.apply_note_change(
      '1'::text, 1::bigint, null::bigint, 'v1'::text, 'body'::text, 100::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
    ))->>'status' $$,
  ARRAY['applied'],
  'the 14-argument overload still works before any wipe'
);

-- --------------------------------------------------------------------------------- the wipe itself

select is(
  public.delete_all_user_cloud_data()->>'wipe_generation',
  '1',
  'the wipe publishes the generation it committed under'
);

select is(
  public.get_sync_generation()->>'wipe_generation',
  '1',
  'the generation survives the wipe'
);

select is(
  (select count(*)::text from public.notes),
  '0',
  'the wipe removed the notes'
);

-- ------------------------------------------------------------------ a device that did not notice

select set_config('notelikeus.wipe_generation', '', true);

select throws_ok(
  $$ select public.apply_note_change(
      '1'::text, 1::bigint, null::bigint, 'resurrect'::text, 'body'::text, 101::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
    ) $$,
  'NL001',
  null,
  'a 14-argument push from before the wipe is refused'
);

select throws_ok(
  $$ select public.apply_note_change(
      '2'::text, 2::bigint, null::bigint, 'resurrect'::text, 'body'::text, 102::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb
    ) $$,
  'NL001',
  null,
  'a 15-argument push with attachment ids from before the wipe is refused'
);

select throws_ok(
  $$ select public.restore_note(
      '1'::text, 1::bigint, null::bigint, 'resurrect'::text, 'body'::text, 103::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
    ) $$,
  'NL001',
  null,
  'a restore from before the wipe is refused'
);

select throws_ok(
  $$ select public.apply_note_change(
      '3'::text, 3::bigint, null::bigint, 'resurrect'::text, 'body'::text, 104::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, 0::bigint
    ) $$,
  'NL002',
  null,
  'a push presenting the pre-wipe generation is refused'
);

select throws_ok(
  $$ select public.apply_note_change(
      '4'::text, 4::bigint, null::bigint, 'resurrect'::text, 'body'::text, 105::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 0::bigint
    ) $$,
  'NL002',
  null,
  'a push with attachment ids presenting the pre-wipe generation is refused'
);

select throws_ok(
  $$ select public.apply_note_change(
      '5'::text, 5::bigint, null::bigint, 'resurrect'::text, 'body'::text, 106::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, null::bigint
    ) $$,
  'NL001',
  null,
  'a push presenting an unknown generation is refused'
);

-- ------------------------------------------------ opening the sync-mutation guard changes nothing

select set_config('notelikeus.wipe_generation', '', true);
select notelikeus_internal.begin_sync_mutation();
select throws_ok(
  $$ insert into public.notes (
       note_id, local_id, owner_id, revision, title, content, client_timestamp, color, position
     ) values (
       '77', 77, auth.uid(), nextval('public.sync_revision_seq'), 'raw', '', 1, 1, 0
     ) $$,
  'NL001',
  null,
  'a raw write cannot bypass the wipe generation by opening the sync-mutation guard'
);
select notelikeus_internal.end_sync_mutation();

-- ------------------------------------------------------------------ a device that did resync

select results_eq(
  $$ select (public.apply_note_change(
      '5'::text, 5::bigint, null::bigint, 'after wipe'::text, 'body'::text, 110::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, 1::bigint
    ))->>'status' $$,
  ARRAY['applied'],
  'a push presenting the current generation is accepted'
);

select is(
  (select title from public.notes where note_id = '5'),
  'after wipe',
  'the accepted push wrote the note'
);

select results_eq(
  $$ select (public.apply_note_change(
      '6'::text, 6::bigint, null::bigint, 'after wipe'::text, 'body'::text, 111::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 1::bigint
    ))->>'status' $$,
  ARRAY['applied'],
  'the attachment-id overload accepts the current generation too'
);

select results_eq(
  $$ select (public.apply_note_change(
      '9'::text, 9::bigint, null::bigint, 'deleted soon'::text, 'body'::text, 112::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, 1::bigint
    ))->>'status' $$,
  ARRAY['applied'],
  'a note to delete and restore exists'
);

-- A delete has to be tested against a note the server actually has: the guard is a row trigger,
-- so a delete for a note that is not there touches no row. That case returns
-- `conflict / note_not_found` and cannot resurrect anything either way.
select set_config('notelikeus.wipe_generation', '', true);
select throws_ok(
  $$ select public.apply_note_delete(
      '9'::text, (select revision from public.notes where note_id = '9')
    ) $$,
  'NL001',
  null,
  'a delete from before the wipe is refused'
);

select throws_ok(
  $$ select public.apply_note_delete(
      '9'::text, (select revision from public.notes where note_id = '9'), 0::bigint
    ) $$,
  'NL002',
  null,
  'a delete presenting the pre-wipe generation is refused'
);

select is(
  (select count(*)::text from public.notes where note_id = '9'),
  '1',
  'the refused deletes left the note in place'
);

select results_eq(
  $$ select (public.apply_note_delete(
      '9'::text, (select revision from public.notes where note_id = '9'), 1::bigint
    ))->>'status' $$,
  ARRAY['applied'],
  'a delete presenting the current generation is accepted'
);

select results_eq(
  $$ select (public.restore_note(
      '9'::text, 9::bigint,
      (select revision from public.note_tombstones where note_id = '9'),
      'restored'::text, 'body'::text, 113::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, 1::bigint
    ))->>'status' $$,
  ARRAY['applied'],
  'a restore presenting the current generation is accepted'
);

select is(
  (select title from public.notes where note_id = '9'),
  'restored',
  'the restored note is live again'
);

-- ------------------------------------------------------------------- the generation only moves up

select is(
  public.delete_all_user_cloud_data()->>'wipe_generation',
  '2',
  'a second wipe increments the generation again'
);

select throws_ok(
  $$ select public.apply_note_change(
      '5'::text, 5::bigint, null::bigint, 'stale again'::text, 'body'::text, 114::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb, 1::bigint
    ) $$,
  'NL002',
  null,
  'the generation the first wipe published is stale after the second'
);

-- ----------------------------------------------------- the generation is the server's, not ours

select set_config('notelikeus.wipe_generation', '', true);
select throws_ok(
  $$ update public.account_sync_state set wipe_generation = 0 where owner_id = auth.uid() $$,
  '42501',
  null,
  'the generation cannot be written directly around the wipe RPC'
);

select tests.authenticate_as('wipe_b@notelikeus.test');
select is(
  (select count(*)::text from public.account_sync_state),
  '0',
  'another account cannot read this account''s wipe state'
);

select tests.clear_authentication();
select throws_ok(
  $$ select public.get_sync_generation() $$,
  '42501',
  null,
  'anon cannot read the wipe generation'
);

-- ---------------------------------------------------------------------------------- the surface

select tests.authenticate_as('wipe_a@notelikeus.test');

select has_function(
  'public', 'apply_note_change',
  ARRAY[
    'text','bigint','bigint','text','text','bigint','integer','boolean','boolean','boolean',
    'integer','bigint','jsonb','jsonb','bigint'
  ],
  'the 15-argument apply_note_change accepts a wipe generation'
);

select has_function(
  'public', 'apply_note_change',
  ARRAY[
    'text','bigint','bigint','text','text','bigint','integer','boolean','boolean','boolean',
    'integer','bigint','jsonb','jsonb','jsonb','bigint'
  ],
  'the 16-argument apply_note_change accepts attachment ids and a wipe generation'
);

select has_function(
  'public', 'apply_note_delete',
  ARRAY['text','bigint','bigint'],
  'apply_note_delete accepts a wipe generation'
);

select has_function(
  'public', 'restore_note',
  ARRAY[
    'text','bigint','bigint','text','text','bigint','integer','boolean','boolean','boolean',
    'integer','bigint','jsonb','jsonb','bigint'
  ],
  'restore_note accepts a wipe generation'
);

select has_function(
  'public', 'get_sync_generation', ARRAY[]::text[],
  'the generation can be read on its own'
);

select ok(
  (select relrowsecurity from pg_class where oid = 'public.account_sync_state'::regclass),
  'account_sync_state has row level security enabled'
);

select * from finish();
rollback;
