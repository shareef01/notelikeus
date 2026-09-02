begin;
select plan(8);

select tests.create_supabase_user('user_a@notelikeus.test');
select tests.create_supabase_user('user_b@notelikeus.test');

-- Anonymous users see no rows (RLS), and cannot mutate via RPC or direct insert.
select tests.clear_authentication();
select results_eq(
  $$ select count(*)::bigint from public.notes $$,
  ARRAY[0::bigint],
  'anonymous cannot read notes'
);
select throws_ok(
  $$ insert into public.notes (
      note_id, local_id, owner_id, revision, title, content, client_timestamp, color
    ) values ('1', 1, gen_random_uuid(), 1, 'x', 'y', 1, 1) $$,
  '42501',
  null,
  'anonymous cannot insert notes'
);
select throws_ok(
  $$ select public.apply_note_change(
      '1'::text, 1::bigint, null::bigint,
      't'::text, 'c'::text, 1::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  '28000',
  null,
  'anonymous cannot call apply_note_change'
);
select results_eq(
  $$ select count(*)::bigint from public.note_tombstones $$,
  ARRAY[0::bigint],
  'anonymous cannot read tombstones'
);

-- User A seeds a note
select tests.authenticate_as('user_a@notelikeus.test');
select results_eq(
  $$ select (public.apply_note_change(
      '7'::text, 7::bigint, null::bigint,
      'A'::text, 'secret'::text, 1::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'user A can create'
);

-- User B isolation (read)
select tests.authenticate_as('user_b@notelikeus.test');
select results_eq(
  $$ select count(*)::bigint from public.notes $$,
  ARRAY[0::bigint],
  'user B cannot read A notes'
);

-- User B can create the same note id in its own namespace
select results_eq(
  $$ select (public.apply_note_change(
      '7'::text, 7::bigint, null::bigint,
      'B-owned'::text, 'body'::text, 1::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'user B can create its own note id 7 in B namespace'
);

select tests.authenticate_as('user_a@notelikeus.test');
select results_eq(
  $$ select title from public.notes where note_id = '7' $$,
  ARRAY['A'::text],
  'A note unchanged while B uses same note id in separate namespace'
);

select * from finish();
rollback;
