begin;
select plan(9);

select tests.create_supabase_user('restore_att_a@notelikeus.test');
select tests.create_supabase_user('restore_att_b@notelikeus.test');

select tests.authenticate_as('restore_att_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.register_note_attachment(
  'att1', '1',
  public.expected_attachment_object_key(auth.uid(), '1', 'att1'),
  'image/png', 12, 'image'
);
select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));

select results_eq(
  $$ select jsonb_array_length(public.list_pending_deleted_attachments()) $$,
  ARRAY[1],
  'deleted note attachments are listed for GC'
);

select public.restore_note(
  '1', 1::bigint, null::bigint,
  'restored', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

select results_eq(
  $$ select count(*)::bigint from public.note_attachments
     where note_id = '1' and deleted_at is null $$,
  ARRAY[1::bigint],
  'restore clears attachment deleted_at in the same transaction'
);
select results_eq(
  $$ select jsonb_array_length(public.list_pending_deleted_attachments()) $$,
  ARRAY[0],
  'restored note attachments are not pending GC'
);
select results_eq(
  $$ select jsonb_array_length(public.list_user_attachments()) $$,
  ARRAY[1],
  'restored note attachments are listed as live'
);
select results_eq(
  $$ select (public.authorize_note_attachment_get('1', 'att1')->>'allowed')::boolean $$,
  ARRAY[true],
  'GET is allowed again after restore'
);

select results_eq(
  $$ select (public.purge_deleted_note_attachment('att1', '1')->>'reason') $$,
  ARRAY['note_live'::text],
  'purge refuses to drop metadata for a live note'
);

select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));
select results_eq(
  $$ select (public.purge_deleted_note_attachment('att1', '1')->>'status') $$,
  ARRAY['applied'::text],
  'purge removes tombstoned attachment metadata'
);
select results_eq(
  $$ select count(*)::bigint from public.note_attachments where attachment_id = 'att1' $$,
  ARRAY[0::bigint],
  'purged metadata row is gone'
);

select tests.authenticate_as('restore_att_b@notelikeus.test');
select results_eq(
  $$ select jsonb_array_length(public.list_pending_deleted_attachments()) $$,
  ARRAY[0],
  'other owner cannot list A pending GC'
);

select * from finish();
rollback;
