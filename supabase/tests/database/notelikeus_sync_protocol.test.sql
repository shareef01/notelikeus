begin;
select plan(24);

select tests.create_supabase_user('sync_a@notelikeus.test');

select tests.authenticate_as('sync_a@notelikeus.test');

-- create → revision 10001 (sequence start)
select results_eq(
  $$ select (public.apply_note_change(
      '1', 1, null, 'v1', 'body', 100, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'create accepted'
);
select results_eq(
  $$ select revision::text from public.notes where note_id = '1' $$,
  ARRAY['10001'],
  'create revision is 10001'
);

-- update with matching base → revision 10002
select results_eq(
  $$ select (public.apply_note_change(
      '1', 1, 10001, 'v2', 'body', 101, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'update with matching base accepted'
);
select results_eq(
  $$ select revision::text from public.notes where note_id = '1' $$,
  ARRAY['10002'],
  'update revision is 10002'
);

-- stale update must not overwrite revision 10002
select results_eq(
  $$ select (public.apply_note_change(
      '1', 1, 10001, 'stale', 'body', 102, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'stale base revision rejected'
);
select results_eq(
  $$ select title from public.notes where note_id = '1' $$,
  ARRAY['v2'::text],
  'stale update did not overwrite note'
);
select results_eq(
  $$ select revision::text from public.notes where note_id = '1' $$,
  ARRAY['10002'],
  'stale update did not advance revision'
);

-- delete with matching base → revision 10003 tombstone
select results_eq(
  $$ select (public.apply_note_delete('1', 10002)->>'status') $$,
  ARRAY['applied'],
  'delete accepted'
);
select results_eq(
  $$ select count(*)::bigint from public.notes where note_id = '1' $$,
  ARRAY[0::bigint],
  'note row removed'
);
select results_eq(
  $$ select revision::text from public.note_tombstones where note_id = '1' $$,
  ARRAY['10003'],
  'delete revision is 10003'
);

-- pull after 10001 returns update + delete in order
select results_eq(
  $$ select jsonb_path_query_array(
      public.pull_changes(10001, 100)->'changes',
      '$[*].revision'
    )::text $$,
  $$ to_jsonb(ARRAY[10002, 10003])::text $$,
  'pull_changes after 10001 returns revisions 10002 and 10003'
);

-- stale device cannot resurrect deleted note (create path)
select results_eq(
  $$ select (public.apply_note_change(
      '1', 1, null, 'resurrected', 'body', 200, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'create after tombstone conflicts'
);
select results_eq(
  $$ select (public.apply_note_change(
      '1', 1, null, 'resurrected', 'body', 200, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'error') $$,
  ARRAY['note_deleted'],
  'resurrection error is note_deleted'
);

-- stale device update with old base also conflicts (note row gone)
select results_eq(
  $$ select (public.apply_note_change(
      '1', 1, 10002, 'stale-after-delete', 'body', 201, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'stale update after delete conflicts'
);
select results_eq(
  $$ select count(*)::bigint from public.notes where note_id = '1' $$,
  ARRAY[0::bigint],
  'note still absent after stale update'
);

-- direct revision forgery blocked
select throws_ok(
  $$ insert into public.notes (
      note_id, local_id, owner_id, revision, title, content, client_timestamp, color
    ) values ('99', 99, auth.uid(), 99999, 'forged', 'x', 1, 1) $$,
  '42501',
  null,
  'direct insert with forged revision rejected'
);

-- direct owner forgery blocked (wrong uuid still goes through RLS as own row only)
select throws_ok(
  $$ insert into public.notes (
      note_id, local_id, owner_id, revision, title, content, client_timestamp, color
    ) values ('98', 98, '00000000-0000-0000-0000-000000000099'::uuid, 1, 'forged', 'x', 1, 1) $$,
  '42501',
  null,
  'direct insert blocked by mutation guard'
);

-- pagination flag
select tests.authenticate_as('sync_a@notelikeus.test');
select results_eq(
  $$ select (public.apply_note_change(
      '2', 2, null, 'n2', 'b', 1, 1, false, false, false, 0, null,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'second note for pagination test'
);
select ok(
  (public.pull_changes(0, 1)->>'has_more')::boolean,
  'pull_changes reports has_more when limited'
);

-- idempotent delete
select results_eq(
  $$ select (public.apply_note_delete('1', 10002)->>'idempotent')::text $$,
  ARRAY['true'],
  'repeated delete is idempotent'
);

select * from finish();
rollback;
