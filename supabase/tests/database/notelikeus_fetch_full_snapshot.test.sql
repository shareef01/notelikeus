begin;
select plan(7);

select tests.create_supabase_user('snapshot_empty@notelikeus.test');
select tests.authenticate_as('snapshot_empty@notelikeus.test');

select ok(
  public.fetch_full_snapshot() is not null,
  'empty library snapshot is JSON, not SQL NULL'
);
select is(
  (public.fetch_full_snapshot()->>'note_count')::integer,
  0,
  'empty library note_count is 0'
);
select is(
  jsonb_array_length(public.fetch_full_snapshot()->'notes'),
  0,
  'empty library notes array is empty'
);
select is(
  jsonb_array_length(public.fetch_full_snapshot()->'tombstones'),
  0,
  'empty library tombstones array is empty'
);

select results_eq(
  $$ select (public.apply_note_change(
      '1'::text, 1::bigint, null::bigint,
      'keep'::text, 'body'::text, 100::bigint, 1::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'create a note so it can be deleted'
);

select results_eq(
  $$ select (public.apply_note_delete(
      '1'::text,
      (select revision from public.notes where note_id = '1')
    )->>'status') $$,
  ARRAY['applied'],
  'delete the only note (tombstone remains)'
);

select ok(
  exists (
    select 1
    from jsonb_array_elements(public.fetch_full_snapshot()->'tombstones') elem
    where elem->>'note_id' = '1'
  )
  and jsonb_array_length(public.fetch_full_snapshot()->'notes') = 0
  and (public.fetch_full_snapshot()->>'note_count')::integer = 0,
  'snapshot still returns tombstones when no notes remain'
);

select * from finish();
rollback;
