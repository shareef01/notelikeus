begin;
select plan(8);

select tests.create_supabase_user('limits@notelikeus.test');
select tests.authenticate_as('limits@notelikeus.test');

select throws_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      repeat('T', 2001), 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  '22023',
  null,
  'oversized title rejected'
);

select throws_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      't', repeat('C', 100001), 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  '22023',
  null,
  'oversized content rejected'
);

select throws_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
      (select jsonb_agg(jsonb_build_object('name', 'L' || g)) from generate_series(1, 101) g),
      '[]'::jsonb
    ) $$,
  '22023',
  null,
  'more than 100 labels rejected'
);

select throws_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb,
      (select jsonb_agg(jsonb_build_object('text', 'i', 'isChecked', false, 'position', g))
         from generate_series(1, 501) g)
    ) $$,
  '22023',
  null,
  'more than 500 checklist items rejected'
);

select throws_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
      '{"name":"not-array"}'::jsonb, '[]'::jsonb
    ) $$,
  '22023',
  null,
  'malformed labels JSON rejected'
);

select throws_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '{"text":"x"}'::jsonb
    ) $$,
  '22023',
  null,
  'malformed checklist JSON rejected'
);

select throws_ok(
  $$ select public.restore_note(
      '1', 1::bigint, null::bigint,
      repeat('T', 2001), 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  '22023',
  null,
  'restore shares the same title limit'
);

select lives_ok(
  $$ select public.apply_note_change(
      '1', 1::bigint, null::bigint,
      repeat('T', 2000), repeat('C', 100000), 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    ) $$,
  'canonical max title and content are accepted'
);

select * from finish();
rollback;
