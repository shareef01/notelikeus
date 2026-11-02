-- Account deletion: every row goes, nothing can come back, and the auth user can then be deleted.
-- See supabase/migrations/20261102010000_account_deletion.sql.

begin;
select plan(28);

select setval('public.sync_revision_seq', 30000, true);

select tests.create_supabase_user('del_a@notelikeus.test');
select tests.create_supabase_user('del_b@notelikeus.test');
select tests.create_supabase_user('del_guarded@notelikeus.test');

-- A second account that must not be touched by anything below.
select tests.authenticate_as('del_b@notelikeus.test');
select public.apply_note_change(
  '1'::text, 1::bigint, null::bigint, 'b'::text, 'body'::text, 100::bigint, 1::integer,
  false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
);

-- ------------------------------------------------- the cascade this design has to work around

select tests.authenticate_as('del_guarded@notelikeus.test');
select public.apply_note_change(
  '1'::text, 1::bigint, null::bigint, 'g'::text, 'body'::text, 100::bigint, 1::integer,
  false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
);
select tests.clear_authentication();
reset role;

-- The Auth admin API deletes this way and cannot open the mutation guard. If this ever starts
-- passing, the purge in request_account_deletion stops being load-bearing.
select throws_ok(
  format('delete from auth.users where id = %L', tests.get_supabase_uid('del_guarded@notelikeus.test')),
  '42501',
  'direct table mutation not allowed',
  'deleting a user who still has notes is refused by the mutation guard'
);

-- --------------------------------------------------------------------- an account with everything

select tests.authenticate_as('del_a@notelikeus.test');

select public.apply_note_change(
  '1'::text, 1::bigint, null::bigint, 'keep'::text, 'body'::text, 100::bigint, 1::integer,
  false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_change(
  '2'::text, 2::bigint, null::bigint, 'gone'::text, 'body'::text, 100::bigint, 1::integer,
  false, false, false, 1::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_delete(
  '2'::text,
  (select revision from public.notes where note_id = '2')
);
select public.finalize_note_attachment_put(
  '1'::text, 'att1'::text,
  public.expected_attachment_object_key(auth.uid(), '1', 'att1'),
  'image/png'::text, 10::bigint, false, 'image'::text
);
-- A wiped account carries a generation, so the purge has to get past that guard as well.
select public.delete_all_user_cloud_data();
select public.present_wipe_generation(1::bigint);
select public.apply_note_change(
  '3'::text, 3::bigint, null::bigint, 'after wipe'::text, 'body'::text, 100::bigint, 1::integer,
  false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '3'::text, 'att3'::text,
  public.expected_attachment_object_key(auth.uid(), '3', 'att3'),
  'image/png'::text, 10::bigint, false, 'image'::text
);
select public.apply_note_delete(
  '3'::text,
  (select revision from public.notes where note_id = '3')
);
select public.present_wipe_generation(1::bigint);
select public.apply_note_change(
  '4'::text, 4::bigint, null::bigint, 'live'::text, 'body'::text, 100::bigint, 1::integer,
  false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
);

-- -------------------------------------------------------------------------------- the request

select is(
  public.request_account_deletion()->>'status',
  'accepted',
  'the request is accepted'
);
select is(
  public.request_account_deletion()->>'status',
  'accepted',
  'repeating the request gives the same answer'
);

select is((select count(*)::int from public.notes), 0, 'every note is gone');
select is((select count(*)::int from public.note_tombstones), 0, 'every tombstone is gone');
select is((select count(*)::int from public.note_attachments), 0, 'every attachment row is gone');
select is((select count(*)::int from public.sync_meta), 0, 'sync_meta is gone');
select is((select count(*)::int from public.account_sync_state), 0, 'the generation row is gone');
select is(
  (select count(*)::int from public.account_deletion_requests),
  1,
  'the request is on record'
);

-- ------------------------------------------------------------------ nothing can treat it as live

select throws_ok(
  $$ select public.fetch_full_snapshot() $$, 'NL003', null, 'a snapshot says the account was deleted'
);
select throws_ok(
  $$ select public.pull_changes(0::bigint, 100::integer) $$, 'NL003', null, 'so does a pull'
);
select throws_ok(
  $$ select public.get_sync_generation() $$, 'NL003', null, 'and asking for the generation'
);
select throws_ok(
  $$ select public.apply_note_change(
      '9'::text, 9::bigint, null::bigint, 'back'::text, 'body'::text, 100::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
    ) $$,
  'NL003', null, 'a stale device cannot put a note back'
);
select throws_ok(
  $$ select public.restore_note(
      '9'::text, 9::bigint, null::bigint, 'back'::text, 'body'::text, 100::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
    ) $$,
  'NL003', null, 'nor restore one'
);
select throws_ok(
  $$ select public.finalize_note_attachment_put(
      '1'::text, 'att9'::text,
      public.expected_attachment_object_key(auth.uid(), '1', 'att9'),
      'image/png'::text, 10::bigint, true, 'image'::text
    ) $$,
  null, null, 'an upload still in flight cannot finalize'
);
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  'NL003', null, 'a wipe from another device cannot recreate the generation row'
);
select is_empty(
  $$ update public.account_deletion_requests set auth_user_deleted_at = now() returning 1 $$,
  'the user cannot change their own request'
);
select is_empty(
  $$ delete from public.account_deletion_requests returning 1 $$,
  'the user cannot withdraw their own request'
);
select throws_ok(
  $$ insert into public.account_deletion_requests (owner_id) values (auth.uid()) $$,
  '42501', null, 'a direct write cannot skip the purge'
);

-- ----------------------------------------------------------------------- the other account is fine

select tests.authenticate_as('del_b@notelikeus.test');
select is((select count(*)::int from public.notes), 1, 'another account keeps its notes');
select is(
  public.fetch_full_snapshot()->>'note_count', '1', 'and can still sync'
);
select is(
  (select count(*)::int from public.account_deletion_requests),
  0,
  'and cannot see the request'
);

-- --------------------------------------------------------------------- who may run the sweep's RPCs

select throws_ok(
  $$ select public.list_pending_account_deletions(25) $$,
  '42501', null, 'a signed-in user cannot list deletions'
);
select throws_ok(
  format(
    'select public.mark_account_auth_user_deleted(%L)',
    tests.get_supabase_uid('del_b@notelikeus.test')
  ),
  '42501', null, 'nor mark one'
);

-- ------------------------------------------------------------------------ the rest of the protocol

select tests.clear_authentication();
reset role;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select is(
  public.complete_account_deletion(tests.get_supabase_uid('del_a@notelikeus.test'))->>'status',
  'skipped',
  'a request whose user still exists cannot be forgotten'
);
select is(
  public.mark_account_auth_user_deleted(tests.get_supabase_uid('del_a@notelikeus.test'))->>'status',
  'applied',
  'the Worker records the auth user going'
);
select is(
  public.complete_account_deletion(tests.get_supabase_uid('del_a@notelikeus.test'))->>'status',
  'skipped',
  'and the fence stays up while an access token could still be live'
);

reset role;

-- With no rows left the cascade has nothing to trip over: this is what the admin API runs.
select lives_ok(
  format('delete from auth.users where id = %L', tests.get_supabase_uid('del_a@notelikeus.test')),
  'the auth user can be deleted once the rows are purged'
);

select * from finish();
rollback;
