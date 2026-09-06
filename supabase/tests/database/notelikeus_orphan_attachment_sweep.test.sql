begin;
select plan(6);

create function tests.backdate_deleted_attachments(p_hours integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  update public.note_attachments
  set deleted_at = timezone('utc', now()) - make_interval(hours => p_hours)
  where deleted_at is not null;
  perform public.end_sync_mutation();
end;
$$;

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

select tests.create_supabase_user('orphan_a@notelikeus.test');
select tests.authenticate_as('orphan_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '1', 'att1',
  public.expected_attachment_object_key(auth.uid(), '1', 'att1'),
  'image/png', 12, 'image'
);
select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));

select throws_ok(
  $$ select public.list_orphaned_deleted_attachments() $$,
  '42501',
  null,
  'authenticated users cannot list the hosted orphan sweep'
);

select throws_ok(
  $$ select public.purge_orphaned_deleted_attachment(
        tests.get_supabase_uid('orphan_a@notelikeus.test'), '1', 'att1'
      ) $$,
  '42501',
  null,
  'authenticated users cannot purge via the hosted orphan sweep'
);

select tests.authenticate_as_service_role();
select results_eq(
  $$ select jsonb_array_length(public.list_orphaned_deleted_attachments()) $$,
  ARRAY[0],
  'fresh deletes stay with the client sweep for 24 hours'
);

select tests.backdate_deleted_attachments(25);
select results_eq(
  $$ select jsonb_array_length(public.list_orphaned_deleted_attachments()) $$,
  ARRAY[1],
  'service_role sees stale tombstoned attachment metadata'
);

select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
        tests.get_supabase_uid('orphan_a@notelikeus.test'), '1', 'att1'
      )->>'status' $$,
  ARRAY['applied'::text],
  'service_role can purge stale orphan metadata'
);

select tests.authenticate_as('orphan_a@notelikeus.test');
select public.apply_note_change(
  '2', 2::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '2', 'att2',
  public.expected_attachment_object_key(auth.uid(), '2', 'att2'),
  'image/png', 12, 'image'
);
select tests.authenticate_as_service_role();
select tests.backdate_deleted_attachments(25);
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
        tests.get_supabase_uid('orphan_a@notelikeus.test'), '2', 'att2'
      )->>'reason' $$,
  ARRAY['note_live'::text],
  'hosted sweep refuses to drop metadata for a live note'
);

select * from finish();
rollback;
