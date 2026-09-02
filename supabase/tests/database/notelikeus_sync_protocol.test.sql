begin;
select plan(18);

select setval('public.sync_revision_seq', 10000, true);

select tests.create_supabase_user('sync_a@notelikeus.test');
select tests.authenticate_as('sync_a@notelikeus.test');

-- create → revision 10001
select results_eq(
  $$ select (public.apply_note_change(
      '1'::text, 1::bigint, null::bigint,
      'v1'::text, 'body'::text, 100::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
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
      '1'::text, 1::bigint, (select revision from public.notes where note_id = '1'),
      'v2'::text, 'body'::text, 101::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
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
      '1'::text, 1::bigint, 10001::bigint,
      'stale'::text, 'body'::text, 102::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
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
  $$ select (public.apply_note_delete(
      '1'::text,
      (select revision from public.notes where note_id = '1')
    )->>'status') $$,
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

-- pull after 10001 returns update + delete revisions
select ok(
  (
    select count(*) = 2
    from jsonb_array_elements(public.pull_changes(10001, 100)->'changes') elem
    where (elem->>'revision') in ('10002', '10003')
  ),
  'pull_changes after 10001 returns update and delete revisions'
);

-- stale device cannot resurrect deleted note (create path)
select results_eq(
  $$ select (public.apply_note_change(
      '1'::text, 1::bigint, null::bigint,
      'resurrected'::text, 'body'::text, 200::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['conflict'],
  'create after tombstone conflicts'
);
select results_eq(
  $$ select (public.apply_note_change(
      '1'::text, 1::bigint, null::bigint,
      'resurrected'::text, 'body'::text, 200::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'error') $$,
  ARRAY['note_deleted'],
  'resurrection error is note_deleted'
);

-- stale device update with old base also conflicts (note row gone)
select results_eq(
  $$ select (public.apply_note_change(
      '1'::text, 1::bigint, 10002::bigint,
      'stale-after-delete'::text, 'body'::text, 201::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
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

-- direct revision / owner forgery blocked by mutation guard
select throws_ok(
  $$ insert into public.notes (
      note_id, local_id, owner_id, revision, title, content, client_timestamp, color
    ) values ('99', 99, auth.uid(), 99999, 'forged', 'x', 1, 1) $$,
  '42501',
  null,
  'direct insert with forged revision rejected'
);
select throws_ok(
  $$ insert into public.notes (
      note_id, local_id, owner_id, revision, title, content, client_timestamp, color
    ) values ('98', 98, '00000000-0000-0000-0000-000000000099'::uuid, 1, 'forged', 'x', 1, 1) $$,
  '42501',
  null,
  'direct insert blocked by mutation guard'
);

-- pagination flag
select results_eq(
  $$ select (public.apply_note_change(
      '2'::text, 2::bigint, null::bigint,
      'n2'::text, 'b'::text, 1::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
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
  $$ select (public.apply_note_delete('1'::text, 10002::bigint)->>'idempotent')::text $$,
  ARRAY['true'],
  'repeated delete is idempotent'
);

select * from finish();
rollback;
