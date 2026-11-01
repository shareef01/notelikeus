begin;
select plan(14);

select setval('public.sync_revision_seq', 20000, true);

select tests.create_supabase_user('conflict_a@notelikeus.test');
select tests.create_supabase_user('conflict_b@notelikeus.test');

select tests.authenticate_as('conflict_a@notelikeus.test');

-- ---------------------------------------------------------------------------
-- Scenario 1: Two offline clients edit the same note, reconnecting in sequence
-- ---------------------------------------------------------------------------
-- Base note created at revision 20001
select results_eq(
  $$ select (public.apply_note_change(
      'conf-1'::text, 1::bigint, null::bigint,
      'Base Title'::text, 'Base Body'::text, 100::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'Scenario 1: Base note created'
);

-- Client 1 reconnects first with base 20001: accepted, advances to 20002
select results_eq(
  $$ select (public.apply_note_change(
      'conf-1'::text, 1::bigint, 20001::bigint,
      'Client 1 Title'::text, 'Client 1 Body'::text, 101::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'Scenario 1: Client 1 update accepted at current base'
);

-- Client 2 reconnects second with stale base 20001: rejected as conflict
select results_eq(
  $$ select (public.apply_note_change(
      'conf-1'::text, 1::bigint, 20001::bigint,
      'Client 2 Title'::text, 'Client 2 Body'::text, 102::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'Scenario 1: Client 2 stale base rejected as conflict'
);

-- Verify Client 1's title is preserved intact
select results_eq(
  $$ select title from public.notes where note_id = 'conf-1' $$,
  ARRAY['Client 1 Title'::text],
  'Scenario 1: Server preserves Client 1 edit without corruption'
);

-- ---------------------------------------------------------------------------
-- Scenario 2: Client A deletes note while offline Client B modifies it
-- ---------------------------------------------------------------------------
select results_eq(
  $$ select (public.apply_note_change(
      'conf-2'::text, 2::bigint, null::bigint,
      'To be deleted'::text, 'Body'::text, 200::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'Scenario 2: Setup note conf-2'
);

-- Client A deletes conf-2
select results_eq(
  $$ select (public.apply_note_delete(
      'conf-2'::text,
      (select revision from public.notes where note_id = 'conf-2')
    )->>'status') $$,
  ARRAY['applied'],
  'Scenario 2: Client A delete applied'
);

-- Offline Client B pushes edit on deleted note: returns conflict with note_deleted error
select results_eq(
  $$ select (public.apply_note_change(
      'conf-2'::text, 2::bigint, 20003::bigint,
      'Offline edit on deleted note'::text, 'Body'::text, 202::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'error') $$,
  ARRAY['note_deleted'],
  'Scenario 2: Offline edit on deleted note returns note_deleted conflict'
);

-- ---------------------------------------------------------------------------
-- Scenario 3: Account wipe fences unsynchronized client mutation
-- ---------------------------------------------------------------------------
-- Record wipe generation, then bump via delete_all_user_cloud_data
select is_empty(
  $$ select public.delete_all_user_cloud_data() $$,
  'Scenario 3: delete_all_user_cloud_data executed'
);

-- Mutation with stale wipe generation (0) is rejected
select throws_ok(
  $$ select public.apply_note_change(
      'conf-3'::text, 3::bigint, null::bigint,
      'After wipe'::text, 'Body'::text, 300::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb,
      0::bigint
    ) $$,
  'P0001',
  NULL,
  'Scenario 3: Mutation with stale wipe generation rejected'
);

-- ---------------------------------------------------------------------------
-- Scenario 5: Retried delete mutation on an already deleted note is idempotent
-- ---------------------------------------------------------------------------
-- Delete of an already deleted note returns idempotent/applied without error
select results_eq(
  $$ select (public.apply_note_delete(
      'conf-2'::text,
      20004::bigint,
      (select wipe_generation from public.account_sync_state where user_id = auth.uid())
    )->>'idempotent')::boolean $$,
  ARRAY[true],
  'Scenario 5: Retried delete mutation is strictly idempotent'
);

-- ---------------------------------------------------------------------------
-- Scenario 6: Cross-account isolation prevents modifying another user note
-- ---------------------------------------------------------------------------
select tests.authenticate_as('conflict_b@notelikeus.test');

-- User B attempts to delete User A's conf-1 note: rejected (conflict / note_not_found)
select results_eq(
  $$ select (public.apply_note_delete(
      'conf-1'::text,
      20002::bigint
    )->>'error') $$,
  ARRAY['note_not_found'],
  'Scenario 6: Cross-account delete returns note_not_found error'
);

-- User B attempts to overwrite User A's conf-1 note: rejected
select results_eq(
  $$ select (public.apply_note_change(
      'conf-1'::text, 1::bigint, 20002::bigint,
      'Cross Account Attack'::text, 'Body'::text, 400::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'Scenario 6: Cross-account update rejected'
);

-- ---------------------------------------------------------------------------
-- Scenario 7: Replaying an obsolete revision is rejected
-- ---------------------------------------------------------------------------
select results_eq(
  $$ select (public.apply_note_change(
      'conf-b-1'::text, 10::bigint, null::bigint,
      'User B v1'::text, 'Body'::text, 500::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'Scenario 7: Setup User B note'
);

select results_eq(
  $$ select (public.apply_note_change(
      'conf-b-1'::text, 10::bigint, (select revision from public.notes where note_id = 'conf-b-1'),
      'User B v2'::text, 'Body'::text, 501::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'Scenario 7: User B update to v2 accepted'
);

select results_eq(
  $$ select (public.apply_note_change(
      'conf-b-1'::text, 10::bigint, 20000::bigint,
      'User B obsolete replay'::text, 'Body'::text, 502::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'Scenario 7: Replay of obsolete revision rejected'
);

select * from finish();
rollback;
