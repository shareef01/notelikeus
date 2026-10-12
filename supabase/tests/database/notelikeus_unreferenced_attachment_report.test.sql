-- find_unreferenced_attachment_objects reports R2 keys with no attachment row, and nothing else.

begin;
select plan(5);

create function tests.authenticate_as_service_role()
returns void
language plpgsql
as $$
begin
  perform set_config('role', 'service_role', true);
  perform set_config('request.jwt.claim.role', 'service_role', true);
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
end;
$$;

select tests.create_supabase_user('unref_a@notelikeus.test');
select tests.authenticate_as('unref_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
-- A live row, and a deleted one: both reference their keys.
select public.finalize_note_attachment_put(
  '1', 'live', public.expected_attachment_object_key(auth.uid(), '1', 'live'), 'image/png', 10, 'image'
);
select public.finalize_note_attachment_put(
  '1', 'gone', public.expected_attachment_object_key(auth.uid(), '1', 'gone'), 'image/png', 10, 'image'
);
select public.begin_note_attachment_delete('1', 'gone');

create temporary table unref_keys as
select
  public.expected_attachment_object_key(tests.get_supabase_uid('unref_a@notelikeus.test'), '1', 'live') as live_key,
  public.expected_attachment_object_key(tests.get_supabase_uid('unref_a@notelikeus.test'), '1', 'gone') as gone_key,
  public.expected_attachment_object_key(tests.get_supabase_uid('unref_a@notelikeus.test'), '1', 'orphan') as orphan_key;
grant select on unref_keys to authenticated, service_role;

select throws_ok(
  $$ select public.find_unreferenced_attachment_objects(array['owners/x/notes/1/y']) $$,
  '42501',
  null,
  'an authenticated user cannot run the unreferenced-object report'
);

select tests.authenticate_as_service_role();
select is(
  public.find_unreferenced_attachment_objects(
    (select array[live_key, gone_key, orphan_key] from unref_keys)
  ),
  (select jsonb_build_array(orphan_key) from unref_keys),
  'only the key with no row at all is reported; live and deleted rows count as referenced'
);
select is(
  public.find_unreferenced_attachment_objects(array[]::text[]),
  '[]'::jsonb,
  'an empty batch reports nothing'
);
select throws_ok(
  $$ select public.find_unreferenced_attachment_objects(
       (select array_agg('owners/x/notes/1/k' || n) from generate_series(1, 1001) as n)
     ) $$,
  '22023',
  null,
  'a batch over 1000 keys is refused'
);
select is(
  (select count(*)::int from public.note_attachments
    where owner_id = tests.get_supabase_uid('unref_a@notelikeus.test')),
  2,
  'the report changes nothing: the two rows it was given are still there, and no row was added'
);

select * from finish();
rollback;
