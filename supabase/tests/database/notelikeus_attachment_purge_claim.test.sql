begin;
select plan(12);

-- Restore and permanent deletion must not both win. The sweeper used to delete the R2 object
-- from a stale listing and only then ask whether the note had come back; a restore in that
-- window left live metadata pointing at bytes that were already gone.

select tests.create_supabase_user('claim_user@notelikeus.test');
select tests.authenticate_as('claim_user@notelikeus.test');

select public.apply_note_change(
  '300', 300::bigint, null::bigint,
  'Claimable', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '300', 'att300',
  public.expected_attachment_object_key(auth.uid(), '300', 'att300'),
  'image/png', 1024
);

-- Delete the note so the attachment becomes sweep-eligible, then age it past retention.
select public.apply_note_delete('300', (select revision from public.notes where note_id = '300'));
update public.note_attachments
set deleted_at = timezone('utc', now()) - interval '48 hours'
where attachment_id = 'att300';

-- 1. An authenticated user cannot claim; this is a service-role operation.
select throws_ok(
  $$ select public.claim_orphaned_attachment_for_delete(
       (select id from auth.users where email = 'claim_user@notelikeus.test'), '300', 'att300') $$,
  null,
  null,
  'authenticated callers cannot claim an attachment for deletion'
);

set local role service_role;

-- 2. A fresh eligible row claims, and hands back the canonical key.
select results_eq(
  $$ select (public.claim_orphaned_attachment_for_delete(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att300')->>'claimed')::boolean $$,
  ARRAY[true],
  'an eligible orphan can be claimed'
);
select results_eq(
  $$ select public.claim_orphaned_attachment_for_delete(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att300')->>'object_key' $$,
  ARRAY[(select public.expected_attachment_object_key(
    (select id from auth.users where email = 'claim_user@notelikeus.test'), '300', 'att300'))],
  'the claim returns the canonical owner-scoped key'
);

-- 3. Re-claiming is allowed, so a failed R2 delete can be retried.
select results_eq(
  $$ select (public.claim_orphaned_attachment_for_delete(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att300')->>'claimed')::boolean $$,
  ARRAY[true],
  'a claim is idempotent so the sweeper can retry'
);

select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att300' and purge_claimed_at is not null $$,
  ARRAY[1],
  'the claim is recorded on the row'
);

reset role;
select tests.authenticate_as('claim_user@notelikeus.test');

-- 4. Restoring the note must NOT bring a claimed attachment back to live: its bytes may be gone.
select public.restore_note(
  '300', 300::bigint, null::bigint,
  'Claimable', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att300' and deleted_at is null $$,
  ARRAY[0],
  'restore does not resurrect an attachment already claimed for deletion'
);
select results_eq(
  $$ select count(*)::integer from public.notes where note_id = '300' $$,
  ARRAY[1],
  'the note itself is still restored'
);

-- 5. The other ordering: an unclaimed attachment restores normally and can no longer be claimed.
select public.finalize_note_attachment_put(
  '300', 'att301',
  public.expected_attachment_object_key(auth.uid(), '300', 'att301'),
  'image/png', 1024
);
select public.finalize_note_attachment_delete('300', 'att301');
update public.note_attachments
set deleted_at = timezone('utc', now()) - interval '48 hours'
where attachment_id = 'att301';
select public.restore_note(
  '300', 300::bigint,
  (select revision from public.notes where note_id = '300'),
  'Claimable', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'att301' and deleted_at is null $$,
  ARRAY[1],
  'an unclaimed attachment is restored normally'
);

set local role service_role;
select results_eq(
  $$ select (public.claim_orphaned_attachment_for_delete(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att301')->>'claimed')::boolean $$,
  ARRAY[false],
  'a restored attachment can no longer be claimed'
);

-- 6. Purging requires the claim, so metadata cannot vanish without deletion being granted.
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att301')->>'reason' $$,
  ARRAY['not_claimed'::text],
  'an unclaimed attachment cannot be purged'
);
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att300')->>'status' $$,
  ARRAY['applied'::text],
  'a claimed attachment is purged'
);
select results_eq(
  $$ select public.purge_orphaned_deleted_attachment(
       (select id from auth.users where email = 'claim_user@notelikeus.test'),
       '300', 'att300')->>'status' $$,
  ARRAY['skipped'::text],
  'purging twice is a no-op'
);

reset role;
select * from finish();
rollback;
