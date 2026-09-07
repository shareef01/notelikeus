begin;
select plan(10);

-- Restore and permanent deletion must not both win. The sweeper used to delete the R2 object
-- from a stale listing and only then ask whether the note had come back; a restore in that
-- window left live metadata pointing at bytes that were already gone. Deletion is now claimed
-- under a row lock before any byte is touched, and restore skips claimed attachments.

-- Same idiom the orphan-sweep test uses: SET ROLE needs membership the test user lacks, so the
-- role is switched through the settings the RPC guard actually reads.
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

select tests.create_supabase_user('claim_user@notelikeus.test');
select tests.authenticate_as('claim_user@notelikeus.test');

-- One note that gets deleted (its attachment becomes sweep-eligible), and one that stays live.
select public.apply_note_change(
  '300', 300::bigint, null::bigint,
  'Claimable', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_change(
  '301', 301::bigint, null::bigint,
  'Stays live', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '300', 'att300',
  public.expected_attachment_object_key(auth.uid(), '300', 'att300'),
  'image/png', 1024
);
select public.finalize_note_attachment_put(
  '301', 'att301',
  public.expected_attachment_object_key(auth.uid(), '301', 'att301'),
  'image/png', 1024
);

select public.apply_note_delete('300', (select revision from public.notes where note_id = '300'));
-- Age both past the retention window so only eligibility, not timing, decides the outcome.
select public.finalize_note_attachment_delete('301', 'att301');
select tests.backdate_deleted_attachments(48);

-- 1. Claiming is a service-role operation.
select throws_ok(
  $$ select public.claim_orphaned_attachment_for_delete(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '300', 'att300') $$,
  '42501',
  null,
  'authenticated callers cannot claim an attachment for deletion'
);

select tests.authenticate_as_service_role();

-- 2. An eligible orphan claims, and hands back the canonical owner-scoped key.
select results_eq(
  $$ select (public.claim_orphaned_attachment_for_delete(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '300', 'att300')->>'claimed')::boolean $$,
  ARRAY[true],
  'an eligible orphan can be claimed'
);
select results_eq(
  $$ select public.claim_orphaned_attachment_for_delete(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '300', 'att300')->>'object_key' $$,
  ARRAY[(select public.expected_attachment_object_key(
    tests.get_supabase_uid('claim_user@notelikeus.test'), '300', 'att300'))],
  'the claim returns the canonical owner-scoped key'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att300' and purge_claimed_at is not null $$,
  ARRAY[1],
  'the claim is recorded on the row'
);

-- 3. A live note is refused, so a restore that already happened always wins.
select results_eq(
  $$ select public.claim_orphaned_attachment_for_delete(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '301', 'att301')->>'reason' $$,
  ARRAY['note_live'::text],
  'an attachment whose note is live cannot be claimed'
);

-- 4. Purging requires the claim, so metadata cannot vanish without deletion being granted.
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '301', 'att301')->>'reason' $$,
  ARRAY['not_claimed'::text],
  'an unclaimed attachment cannot be purged'
);

select tests.authenticate_as('claim_user@notelikeus.test');

-- 5. Restoring the note must NOT bring a claimed attachment back to live: its bytes are gone.
select results_eq(
  $$ select (public.restore_note(
       '300', 300::bigint, null::bigint,
       'Claimable', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
       '[]'::jsonb, '[]'::jsonb)->>'status') $$,
  ARRAY['applied'::text],
  'the note itself is restored'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att300' and deleted_at is null $$,
  ARRAY[0],
  'restore does not resurrect an attachment already claimed for deletion'
);

-- 6. The claimed row is still purgeable afterwards, so the sweep converges.
select tests.authenticate_as_service_role();
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '300', 'att300')->>'status' $$,
  ARRAY['applied'::text],
  'a claimed attachment is purged even after its note was restored'
);
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
       tests.get_supabase_uid('claim_user@notelikeus.test'), '300', 'att300')->>'status' $$,
  ARRAY['skipped'::text],
  'purging twice is a no-op'
);

select * from finish();
rollback;
