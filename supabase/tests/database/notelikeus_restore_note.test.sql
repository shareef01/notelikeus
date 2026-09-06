begin;
select plan(10);

select setval('public.sync_revision_seq', 20000, true);
select tests.create_supabase_user('restore_a@notelikeus.test');
select tests.create_supabase_user('restore_b@notelikeus.test');

select tests.authenticate_as('restore_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  'v1', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));

select results_eq(
  $$ select (public.restore_note(
      '1', 1::bigint, null::bigint,
      'restored', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'owner can restore own tombstoned note'
);
select results_eq(
  $$ select count(*)::bigint from public.notes where note_id = '1' $$,
  ARRAY[1::bigint],
  'restored note row exists'
);
select results_eq(
  $$ select count(*)::bigint from public.note_tombstones where note_id = '1' $$,
  ARRAY[0::bigint],
  'owner tombstone removed atomically with restore'
);
select results_eq(
  $$ select title from public.notes where note_id = '1' $$,
  ARRAY['restored'::text],
  'restored payload written'
);
select ok(
  (select revision from public.notes where note_id = '1') > 20001,
  'restored note receives a newer revision'
);

select tests.authenticate_as('restore_b@notelikeus.test');
select public.restore_note(
  '1', 1::bigint, null::bigint,
  'intruder', 'x', 1::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select tests.authenticate_as('restore_a@notelikeus.test');
select results_eq(
  $$ select title from public.notes where note_id = '1' and owner_id = tests.get_supabase_uid('restore_a@notelikeus.test') $$,
  ARRAY['restored'::text],
  'other owner restore cannot overwrite A note'
);

select tests.authenticate_as('restore_a@notelikeus.test');
select results_eq(
  $$ select (public.restore_note(
      '1', 1::bigint, null::bigint,
      'again', 'body', 102::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'repeated restore is idempotent applied'
);
select results_eq(
  $$ select title from public.notes
     where note_id = '1'
       and owner_id = tests.get_supabase_uid('restore_a@notelikeus.test') $$,
  ARRAY['again'::text],
  'repeated restore updates the live note'
);

select throws_ok(
  $$ select public.restore_note(
      '9', 1::bigint, null::bigint,
      'bad', 'x', 1::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  '22023',
  null,
  'invalid note/local identity rejected'
);

select tests.clear_authentication();
select throws_ok(
  $$ select public.restore_note(
      '1', 1::bigint, null::bigint,
      'x', 'y', 1::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  '42501',
  null,
  'anonymous cannot restore'
);

select * from finish();
rollback;
