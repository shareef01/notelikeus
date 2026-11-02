begin;
select plan(10);

select tests.create_supabase_user('quota_a@notelikeus.test');
select tests.authenticate_as('quota_a@notelikeus.test');

-- ---------------------------------------------------------------------------
-- Revision writers hold the per-owner lock until commit
-- ---------------------------------------------------------------------------
select is(
  (select count(*)::int from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('apply_note_change', 'apply_note_delete', 'restore_note')
     and p.prosrc like '%nextval(%'
     and p.prosrc like '%note_sync_write:%'),
  (select count(*)::int from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('apply_note_change', 'apply_note_delete', 'restore_note')
     and p.prosrc like '%nextval(%'),
  'every RPC that allocates a revision takes the per-owner lock'
);

select is(
  (public.apply_note_change(
    '1'::text, 1::bigint, null::bigint, 'One'::text, ''::text, 100::bigint, 1::integer,
    false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
  )->>'status'),
  'applied',
  'first note created'
);

select ok(
  exists (
    select 1 from pg_locks l
    where l.locktype = 'advisory'
      and l.pid = pg_backend_pid()
      and ((l.classid::bigint << 32) | l.objid::bigint)
        = hashtextextended('note_sync_write:' || auth.uid()::text, 0)
  ),
  'the lock is still held after the write, so it lasts until commit'
);

select is(
  (public.apply_note_change(
    '2'::text, 2::bigint, null::bigint, 'Two'::text, ''::text, 100::bigint, 1::integer,
    false, false, false, 0::integer, null::bigint, '[]'::jsonb, '[]'::jsonb
  )->>'status'),
  'applied',
  'second note created'
);

-- ---------------------------------------------------------------------------
-- Quota counts stored bytes: 20 attachments of 10 MB fill note 1 and the quota
-- ---------------------------------------------------------------------------
select count(public.finalize_note_attachment_put(
  '1', 'fill' || i, public.expected_attachment_object_key(auth.uid(), '1', 'fill' || i),
  'image/png', 10485760::bigint, 'image'
)) from generate_series(1, 20) as i;

select throws_ok(
  $$ select public.finalize_note_attachment_put(
       '2', 'over', public.expected_attachment_object_key(auth.uid(), '2', 'over'),
       'image/png', 10485760::bigint, 'image') $$,
  '22023',
  'user attachment quota exceeded',
  'a full account is refused'
);

select is(
  (public.apply_note_delete('1', (select revision from public.notes where note_id = '1'))->>'status'),
  'applied',
  'deleting the note soft-deletes its attachments'
);

select is(
  (public.precheck_note_attachment_put('2', 'over', 'image/png', 10485760::bigint)->>'allowed'),
  'false',
  'deleting a note frees no quota while its objects are still stored'
);

select is(
  (public.finalize_note_attachment_delete('1', 'fill1')->>'confirmed'),
  'true',
  'one object confirmed gone'
);

select is(
  (public.precheck_note_attachment_put('2', 'over', 'image/png', 10485760::bigint)->>'allowed'),
  'true',
  'a confirmed object deletion frees its quota'
);

-- ---------------------------------------------------------------------------
-- A legacy soft-deleted row on a live note: nothing reclaims it, so it must not count
-- ---------------------------------------------------------------------------
select public.finalize_note_attachment_put(
  '2', 'legacy', public.expected_attachment_object_key(auth.uid(), '2', 'legacy'),
  'image/png', 1000::bigint, 'image'
);
select notelikeus_internal.begin_sync_mutation();
update public.note_attachments set deleted_at = timezone('utc', now()) where attachment_id = 'legacy';
select notelikeus_internal.end_sync_mutation();

select is(
  notelikeus_internal.attachment_stored_bytes(auth.uid(), 'none'),
  19 * 10485760::bigint,
  'an unclaimed soft-deleted row on a live note holds no quota'
);

select * from finish();
rollback;
