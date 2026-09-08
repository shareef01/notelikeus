begin;
select plan(30);

-- A user-initiated attachment delete is claimed before its R2 object is touched.
--
-- The Worker used to delete the object and only then ask the database to mark the metadata
-- deleted, without checking the answer, so a refused or failed finalization left a live row
-- pointing at bytes that were gone. Recording the deletion first makes every later failure an
-- orphaned object instead, and the claim is what lets the sweeper finish the job.

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

-- note_attachments is behind a mutation guard, so the test cannot backdate it with a raw UPDATE.
create function tests.backdate_delete_claims(p_hours integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  update public.note_attachments
  set delete_claimed_at = timezone('utc', now()) - make_interval(hours => p_hours)
  where delete_claimed_at is not null;
  perform public.end_sync_mutation();
end;
$$;

select tests.create_supabase_user('del_user_a@notelikeus.test');
select tests.create_supabase_user('del_user_b@notelikeus.test');

-- 1. Anonymous callers reach none of the new surface.
select tests.clear_authentication();
select throws_ok(
  $$ select public.begin_note_attachment_delete('1', 'att1') $$,
  '42501',
  null,
  'anon cannot begin an attachment delete'
);
select throws_ok(
  $$ select public.precheck_note_attachment_put('1', 'att1', 'image/png', 100) $$,
  '42501',
  null,
  'anon cannot precheck an attachment upload'
);

select tests.authenticate_as('del_user_a@notelikeus.test');

-- 2. The sweeper's half is service-role only, even for an authenticated owner.
select throws_ok(
  $$ select public.list_unconfirmed_attachment_deletes(10) $$,
  '42501',
  null,
  'authenticated callers cannot list unconfirmed deletes'
);
select throws_ok(
  $$ select public.confirm_attachment_object_deleted(
       tests.get_supabase_uid('del_user_a@notelikeus.test'), '400', 'att400') $$,
  '42501',
  null,
  'authenticated callers cannot confirm an object deletion'
);

select public.apply_note_change(
  '400', 400::bigint, null::bigint,
  'Owned', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '400', 'att400',
  public.expected_attachment_object_key(auth.uid(), '400', 'att400'),
  'image/png', 1024
);

-- 3. The upload precheck answers ownership without seeing a single byte.
select results_eq(
  $$ select (public.precheck_note_attachment_put('400', 'att400', 'image/png', null)->>'allowed')::boolean $$,
  ARRAY[true],
  'precheck allows an upload to an owned live note'
);
select results_eq(
  $$ select public.precheck_note_attachment_put('400', 'att400', 'image/png', null)->>'object_key' $$,
  ARRAY[(select public.expected_attachment_object_key(
    tests.get_supabase_uid('del_user_a@notelikeus.test'), '400', 'att400'))],
  'precheck returns the canonical owner-scoped key'
);
select results_eq(
  $$ select (public.precheck_note_attachment_put('404', 'att404', 'image/png', null)->>'allowed')::boolean $$,
  ARRAY[false],
  'precheck refuses a note that does not exist'
);
select results_eq(
  $$ select (public.precheck_note_attachment_put('400', 'att400', 'text/html', null)->>'allowed')::boolean $$,
  ARRAY[false],
  'precheck refuses a mime type the store will not accept'
);
select results_eq(
  $$ select (public.precheck_note_attachment_put('400', 'att400', 'image/png', 10485761)->>'allowed')::boolean $$,
  ARRAY[false],
  'precheck refuses a declared size over the per-attachment cap'
);
-- The precheck is advisory only: it must not write anything.
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att404' $$,
  ARRAY[0],
  'a refused precheck creates no metadata'
);

-- 4. Beginning a delete marks the row deleted and claimed, before any byte is touched.
select results_eq(
  $$ select (public.begin_note_attachment_delete('400', 'att400')->>'allowed')::boolean $$,
  ARRAY[true],
  'the owner may begin deleting their own attachment'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att400'
       and deleted_at is not null
       and delete_claimed_at is not null
       and object_deleted_at is null $$,
  ARRAY[1],
  'the claim is recorded and the object is not yet confirmed deleted'
);
select results_eq(
  $$ select public.begin_note_attachment_delete('400', 'att400')->>'object_key' $$,
  ARRAY[(select public.expected_attachment_object_key(
    tests.get_supabase_uid('del_user_a@notelikeus.test'), '400', 'att400'))],
  'a repeated begin returns the same canonical key'
);
select results_eq(
  $$ select (public.begin_note_attachment_delete('400', 'att400')->>'already_claimed')::boolean $$,
  ARRAY[true],
  'a repeated begin reports the existing claim instead of re-claiming'
);

-- 5. Finalization records that the object is gone, and is idempotent.
select results_eq(
  $$ select (public.finalize_note_attachment_delete('400', 'att400')->>'confirmed')::boolean $$,
  ARRAY[true],
  'finalization confirms the deletion'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att400' and object_deleted_at is not null $$,
  ARRAY[1],
  'the confirmation is stamped on the row'
);
select results_eq(
  $$ select (public.finalize_note_attachment_delete('400', 'att400')->>'confirmed')::boolean $$,
  ARRAY[true],
  'finalizing twice is a no-op that still reports success'
);

-- 6. A restore must not resurrect an attachment the owner deleted: its bytes are gone.
select public.apply_note_delete('400', (select revision from public.notes where note_id = '400'));
select results_eq(
  $$ select public.restore_note(
       '400', 400::bigint, null::bigint,
       'Owned', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
       '[]'::jsonb, '[]'::jsonb)->>'status' $$,
  ARRAY['applied'::text],
  'the note itself is restored'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att400' and deleted_at is null $$,
  ARRAY[0],
  'restore does not resurrect an attachment the owner deleted'
);

-- 7. An attachment deleted only as a side effect of deleting its note still comes back.
select public.finalize_note_attachment_put(
  '400', 'att-side',
  public.expected_attachment_object_key(auth.uid(), '400', 'att-side'),
  'image/png', 512
);
select public.apply_note_delete('400', (select revision from public.notes where note_id = '400'));
select public.restore_note(
  '400', 400::bigint, null::bigint,
  'Owned', 'body', 102::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att-side' and deleted_at is null $$,
  ARRAY[1],
  'an unclaimed attachment is still restored with its note'
);

-- 8. No user can claim, delete, or confirm another user's attachment.
select tests.authenticate_as('del_user_b@notelikeus.test');
select results_eq(
  $$ select (public.begin_note_attachment_delete('400', 'att-side')->>'allowed')::boolean $$,
  ARRAY[false],
  'another user cannot begin deleting an attachment they do not own'
);
select results_eq(
  $$ select (public.precheck_note_attachment_put('400', 'att-side', 'image/png', null)->>'allowed')::boolean $$,
  ARRAY[false],
  'another user cannot precheck an upload into a note they do not own'
);

-- 9. Back as the owner -- RLS hides A's rows from B, so only A can see the row survived.
select tests.authenticate_as('del_user_a@notelikeus.test');
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att-side' and deleted_at is null $$,
  ARRAY[1],
  'the other user attachment is untouched by the refused claim'
);

-- 10. The sweeper finds an abandoned claim and can finish it.
select public.begin_note_attachment_delete('400', 'att-side');
select tests.backdate_delete_claims(6);
select tests.authenticate_as_service_role();
select results_eq(
  $$ select jsonb_array_length(public.list_unconfirmed_attachment_deletes(50)) $$,
  ARRAY[1],
  'an abandoned claim is listed for the sweeper'
);
select results_eq(
  $$ select public.confirm_attachment_object_deleted(
       tests.get_supabase_uid('del_user_a@notelikeus.test'), '400', 'att-side')->>'status' $$,
  ARRAY['applied'::text],
  'the sweeper can confirm the object deletion'
);
select results_eq(
  $$ select jsonb_array_length(public.list_unconfirmed_attachment_deletes(50)) $$,
  ARRAY[0],
  'a confirmed row drops out of the listing'
);

-- 11. The cloud wipe still authorizes every object delete through the new protocol.
--     The wipe soft-deletes and marks metadata rather than destroying it precisely so the Worker
--     can still authorize each DELETE against the row; that has to keep working.
select tests.authenticate_as('del_user_a@notelikeus.test');
select public.apply_note_change(
  '600', 600::bigint, null::bigint,
  'Wiped', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '600', 'att600',
  public.expected_attachment_object_key(auth.uid(), '600', 'att600'),
  'image/png', 1024
);
select results_eq(
  $$ select jsonb_array_length(public.delete_all_user_cloud_data()->'attachment_object_keys') > 0 $$,
  ARRAY[true],
  'the wipe hands back object keys to delete'
);
select results_eq(
  $$ select (public.begin_note_attachment_delete('600', 'att600')->>'allowed')::boolean $$,
  ARRAY[true],
  'a wiped attachment can still be claimed for deletion by its owner'
);
select results_eq(
  $$ select (public.finalize_note_attachment_delete('600', 'att600')->>'confirmed')::boolean $$,
  ARRAY[true],
  'a wiped attachment deletion can still be confirmed'
);
select results_eq(
  $$ select (public.finalize_cloud_wipe()->>'attachments_purged')::integer > 0 $$,
  ARRAY[true],
  'the wipe purges its marked rows once the objects are gone'
);

select * from finish();
rollback;
