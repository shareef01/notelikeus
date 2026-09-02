begin;
select plan(9);

select tests.create_supabase_user('wipe_a@notelikeus.test');
select tests.create_supabase_user('wipe_b@notelikeus.test');

select tests.clear_authentication();
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  '28000',
  null,
  'anonymous cannot wipe cloud data'
);

select tests.authenticate_as('wipe_a@notelikeus.test');
select results_eq(
  $$ select (public.apply_note_change(
      '11'::text, 11::bigint, null::bigint,
      'A'::text, 'secret'::text, 1::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'user A seeds a note'
);
select results_eq(
  $$ select (public.apply_note_delete(
      '11'::text,
      (select revision from public.notes where note_id = '11')
    )->>'status') $$,
  ARRAY['applied'],
  'user A deletes the note (tombstone remains)'
);
select lives_ok(
  $$ select public.link_firebase_uid('firebase-uid-a') $$,
  'user A links a firebase uid'
);

select tests.authenticate_as('wipe_b@notelikeus.test');
select results_eq(
  $$ select (public.apply_note_change(
      '22'::text, 22::bigint, null::bigint,
      'B'::text, 'keep'::text, 1::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'user B seeds a note'
);
select results_eq(
  $$ select (public.delete_all_user_cloud_data()->>'notes_deleted')::integer $$,
  ARRAY[1],
  'user B wipe deletes only B notes'
);

select tests.authenticate_as('wipe_a@notelikeus.test');
select results_eq(
  $$ select count(*)::bigint from public.note_tombstones $$,
  ARRAY[1::bigint],
  'user A tombstone survives B wipe'
);
select results_eq(
  $$ select (public.delete_all_user_cloud_data()->>'tombstones_deleted')::integer $$,
  ARRAY[1],
  'user A wipe removes own tombstones'
);
select results_eq(
  $$ select (public.delete_all_user_cloud_data()->>'notes_deleted')::integer $$,
  ARRAY[0],
  'wipe is idempotent'
);

select * from finish();
rollback;
