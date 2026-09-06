begin;
select plan(4);

select tests.create_supabase_user('lookup_a@notelikeus.test');
select tests.create_supabase_user('lookup_b@notelikeus.test');
select tests.authenticate_as('lookup_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

select results_eq(
  $$ select (public.lookup_note_revision('1')->>'exists')::boolean $$,
  ARRAY[true],
  'owner sees own live note'
);

select tests.authenticate_as('lookup_b@notelikeus.test');
select results_eq(
  $$ select (public.lookup_note_revision('1')->>'exists')::boolean $$,
  ARRAY[false],
  'other owner does not see A note'
);

select tests.authenticate_as('lookup_a@notelikeus.test');
select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));
select results_eq(
  $$ select (public.lookup_note_revision('1')->>'tombstoned')::boolean $$,
  ARRAY[true],
  'owner sees own tombstone'
);

select results_eq(
  $$ select (public.lookup_note_revision('missing')->>'exists')::boolean $$,
  ARRAY[false],
  'absent note is confirmed absent'
);

select * from finish();
rollback;
