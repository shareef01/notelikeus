begin;
select plan(20);

-- AUD-003: no call a signed-in user can make removes or discounts an attachment row while its R2
-- object is unconfirmed, so the quota can never be reset with the bytes still stored.
-- AUD-007: a cloud wipe takes the same per-owner locks as the writers it races, in the same order.
-- The genuinely concurrent half of AUD-007 needs two connections and lives in
-- scripts/ops/wipe-lock-race.test.sh.

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

-- note_attachments is behind a mutation guard, so the sweep-eligibility checks cannot backdate it
-- with a raw UPDATE.
create function tests.backdate_attachments(p_hours integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform notelikeus_internal.begin_sync_mutation();
  update public.note_attachments
  set deleted_at = deleted_at - make_interval(hours => p_hours),
      delete_claimed_at = delete_claimed_at - make_interval(hours => p_hours);
  perform notelikeus_internal.end_sync_mutation();
end;
$$;

select tests.create_supabase_user('outlive_a@notelikeus.test');
select tests.create_supabase_user('outlive_b@notelikeus.test');
select tests.authenticate_as('outlive_a@notelikeus.test');

select public.apply_note_change(
  '1', 1::bigint, null::bigint, 'One', '', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select count(public.finalize_note_attachment_put(
  '1', 'big' || i, public.expected_attachment_object_key(auth.uid(), '1', 'big' || i),
  'image/png', 10485760::bigint, 'image'
)) from generate_series(1, 20) as i;

-- ---------------------------------------------------------------------------
-- 1. Wipe then finalize, with no object deleted in between: the bypass
-- ---------------------------------------------------------------------------
select is(
  notelikeus_internal.attachment_stored_bytes(auth.uid(), ''),
  20 * 10485760::bigint,
  'twenty stored objects count against the quota'
);
select public.delete_all_user_cloud_data();
select is(
  (public.finalize_cloud_wipe()->>'attachments_purged')::integer,
  0,
  'finalizing a wipe whose objects were never deleted purges no row'
);
select is(
  (public.finalize_cloud_wipe()->>'attachments_pending')::integer,
  20,
  'finalize reports every unconfirmed row as still pending, and repeating it changes nothing'
);
select is(
  notelikeus_internal.attachment_stored_bytes(auth.uid(), ''),
  20 * 10485760::bigint,
  'the quota still counts the bytes after finalize'
);

-- The account is full, so a new upload is still refused.
select public.apply_note_change(
  '2', 2::bigint, null::bigint, 'Two', '', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb, 1::bigint
);
select is(
  (public.precheck_note_attachment_put('2', 'after', 'image/png', 10485760::bigint)->>'allowed'),
  'false',
  'a wiped-but-unconfirmed account cannot upload past its quota'
);

-- ---------------------------------------------------------------------------
-- 2. The user's token cannot write the confirmation itself
-- ---------------------------------------------------------------------------
select is(
  (public.finalize_note_attachment_delete('1', 'big1')->>'confirmed'),
  'false',
  'finalize_note_attachment_delete called directly confirms nothing'
);
select is(
  (select count(*)::integer from public.note_attachments where object_deleted_at is not null),
  0,
  'no row carries an object confirmation the service role did not write'
);
select is(
  (public.finalize_cloud_wipe()->>'attachments_purged')::integer,
  0,
  'a claimed but unconfirmed row is still not purged'
);
select throws_ok(
  $$ select public.confirm_attachment_object_deleted(auth.uid(), '1', 'big1') $$,
  '42501',
  null,
  'the user cannot call the service-role confirmation'
);

-- ---------------------------------------------------------------------------
-- 3. Legitimate completion: the Worker confirmed, so finalize purges and quota frees
-- ---------------------------------------------------------------------------
select tests.authenticate_as_service_role();
select public.confirm_attachment_object_deleted(
  tests.get_supabase_uid('outlive_a@notelikeus.test'), '1', 'big1');
select tests.authenticate_as('outlive_a@notelikeus.test');
select is(
  (public.finalize_cloud_wipe()->>'attachments_purged')::integer,
  1,
  'a retried finalize purges exactly the confirmed row'
);
select is(
  notelikeus_internal.attachment_stored_bytes(auth.uid(), ''),
  19 * 10485760::bigint,
  'purging a confirmed row releases exactly its bytes'
);
select is(
  (public.precheck_note_attachment_put('2', 'after', 'image/png', 10485760::bigint)->>'allowed'),
  'true',
  'freed quota is usable again'
);

-- ---------------------------------------------------------------------------
-- 4. Unconfirmed rows stay reclaimable by the existing sweeps
-- ---------------------------------------------------------------------------
select tests.backdate_attachments(25);
select tests.authenticate_as_service_role();
select is(
  (select count(*)::integer
   from jsonb_array_elements(public.list_unconfirmed_attachment_deletes(100))),
  0,
  'no claimed row is waiting: big1 was confirmed and the rest are unclaimed'
);
select is(
  (select count(*)::integer
   from jsonb_array_elements(public.list_orphaned_deleted_attachments(100)) e
   where e->>'owner_id' = tests.get_supabase_uid('outlive_a@notelikeus.test')::text),
  19,
  'the orphan sweep still finds every wipe-marked row whose object is unconfirmed'
);

-- ---------------------------------------------------------------------------
-- 5. purge_deleted_note_attachment: the client sweep's purge
-- ---------------------------------------------------------------------------
select tests.authenticate_as('outlive_b@notelikeus.test');
select public.apply_note_change(
  '7', 7::bigint, null::bigint, 'Seven', '', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '7', 'pic', public.expected_attachment_object_key(auth.uid(), '7', 'pic'),
  'image/png', 4096::bigint, 'image'
);
select public.apply_note_delete('7', (select revision from public.notes where note_id = '7'));
select is(
  (public.purge_deleted_note_attachment('pic', '7')->>'reason'),
  'object_unconfirmed',
  'the purge refuses a row whose object was not confirmed deleted'
);
select is(
  notelikeus_internal.attachment_stored_bytes(auth.uid(), ''),
  4096::bigint,
  'and the quota keeps counting it'
);

-- ---------------------------------------------------------------------------
-- 6. Delete, clear the tombstone, re-create the id: the row must still count
-- ---------------------------------------------------------------------------
select public.clear_note_tombstone('7');
select public.apply_note_change(
  '7', 7::bigint, null::bigint, 'Seven again', '', 101::bigint, 0, false, false, false, 0,
  null::bigint, '[]'::jsonb, '[]'::jsonb
);
select is(
  (select count(*)::integer from public.note_attachments
   where attachment_id = 'pic' and deleted_at is not null and delete_claimed_at is null),
  1,
  'the re-created note leaves a soft-deleted, unclaimed row behind'
);
select is(
  notelikeus_internal.attachment_stored_bytes(auth.uid(), ''),
  4096::bigint,
  'which still counts against the quota'
);

-- ---------------------------------------------------------------------------
-- 7. AUD-007: the wipe takes both per-owner locks, writer lock first. Whether it actually waits for
--    an open writer is scripts/ops/wipe-lock-race.test.sh: in this one transaction the earlier calls
--    already hold both locks, so pg_locks here would prove nothing.
-- ---------------------------------------------------------------------------
select ok(
  (select position('note_sync_write:' in p.prosrc) between 1 and position('note_attachment_quota:' in p.prosrc)
   from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'delete_all_user_cloud_data'),
  'the wipe takes the writer lock, then the quota lock'
);
-- Deadlock freedom: no function anywhere takes the quota lock before the writer lock.
select is(
  (select count(*)::integer
   from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname in ('public', 'notelikeus_internal')
     and position('note_sync_write:' in p.prosrc) > 0
     and position('note_attachment_quota:' in p.prosrc) > 0
     and position('note_attachment_quota:' in p.prosrc) < position('note_sync_write:' in p.prosrc)),
  0,
  'no function takes the two per-owner locks in the opposite order'
);

select * from finish();
rollback;
