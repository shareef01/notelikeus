begin;
select plan(14);

-- The wipe has to leave every attachment object reachable for deletion. It previously returned
-- only the keys of rows that were still live, then hard-deleted every row — so already
-- soft-deleted attachments were never listed, and the rows the Worker authorizes DELETEs against
-- were gone before the client could use them.

select tests.create_supabase_user('wipe_user_a@notelikeus.test');
select tests.create_supabase_user('wipe_user_b@notelikeus.test');

select tests.authenticate_as('wipe_user_a@notelikeus.test');

select public.apply_note_change(
  '100', 100::bigint, null::bigint,
  'Live note', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_change(
  '101', 101::bigint, null::bigint,
  'Second note', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

-- One live attachment, and one that the user already deleted before the wipe.
select public.finalize_note_attachment_put(
  '100', 'attlive',
  public.expected_attachment_object_key(auth.uid(), '100', 'attlive'),
  'image/png', 1024
);
select public.finalize_note_attachment_put(
  '101', 'attgone',
  public.expected_attachment_object_key(auth.uid(), '101', 'attgone'),
  'image/png', 2048
);
select public.finalize_note_attachment_delete('101', 'attgone');

select results_eq(
  $$ select count(*)::integer from public.note_attachments where deleted_at is not null $$,
  ARRAY[1],
  'one attachment is soft-deleted before the wipe'
);

-- 1. The wipe lists BOTH objects, including the already soft-deleted one.
select results_eq(
  $$ select jsonb_array_length(public.delete_all_user_cloud_data()->'attachment_object_keys') $$,
  ARRAY[2],
  'wipe returns every owned object key, including already soft-deleted ones'
);

-- 2. Metadata survives the wipe so the Worker can still authorize the deletions.
select results_eq(
  $$ select count(*)::integer from public.note_attachments where owner_id = auth.uid() $$,
  ARRAY[2],
  'wipe keeps attachment metadata until the objects are gone'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where owner_id = auth.uid() and purge_requested_at is not null $$,
  ARRAY[2],
  'wipe marks every attachment for purge'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where owner_id = auth.uid() and deleted_at is null $$,
  ARRAY[0],
  'wipe soft-deletes every attachment'
);

-- 3. Notes, tombstones and sync state are gone as before.
select results_eq(
  $$ select count(*)::integer from public.notes where owner_id = auth.uid() $$,
  ARRAY[0],
  'wipe deletes the notes'
);

-- 4. The Worker's DELETE authorization still succeeds against the marked rows. This is the
--    regression: with the metadata destroyed, every DELETE was refused and no object was removed.
select results_eq(
  $$ select (public.authorize_note_attachment_delete('100', 'attlive')->>'allowed')::boolean $$,
  ARRAY[true],
  'attachment DELETE is still authorized after the wipe'
);
select results_eq(
  $$ select (public.authorize_note_attachment_delete('101', 'attgone')->>'allowed')::boolean $$,
  ARRAY[true],
  'a previously soft-deleted attachment is also still authorized'
);

-- 5. Re-running the wipe is idempotent and still lists what remains.
select results_eq(
  $$ select jsonb_array_length(public.delete_all_user_cloud_data()->'attachment_object_keys') $$,
  ARRAY[2],
  'a repeated wipe still lists the objects that are still stored'
);

-- 6. Finalizing drops the marked rows once the objects are gone.
select results_eq(
  $$ select (public.finalize_cloud_wipe()->>'attachments_purged')::integer $$,
  ARRAY[2],
  'finalize purges the marked attachment metadata'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments where owner_id = auth.uid() $$,
  ARRAY[0],
  'no attachment metadata remains once the wipe is finalized'
);
select results_eq(
  $$ select (public.finalize_cloud_wipe()->>'attachments_purged')::integer $$,
  ARRAY[0],
  'finalizing twice is a no-op'
);

-- 7. Ownership: another account's wipe must not touch these rows, and cannot purge them either.
select tests.authenticate_as('wipe_user_b@notelikeus.test');
select public.apply_note_change(
  '200', 200::bigint, null::bigint,
  'B note', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '200', 'attb',
  public.expected_attachment_object_key(auth.uid(), '200', 'attb'),
  'image/png', 512
);
select tests.authenticate_as('wipe_user_a@notelikeus.test');
select public.delete_all_user_cloud_data();
select public.finalize_cloud_wipe();

select tests.authenticate_as('wipe_user_b@notelikeus.test');
select results_eq(
  $$ select count(*)::integer from public.note_attachments where owner_id = auth.uid() $$,
  ARRAY[1],
  'one account wiping does not touch another account attachment metadata'
);

-- 8. Anonymous callers cannot run either phase.
select tests.clear_authentication();
select throws_ok(
  $$ select public.delete_all_user_cloud_data() $$,
  '42501',
  null,
  'anon cannot wipe cloud data'
);
select throws_ok(
  $$ select public.finalize_cloud_wipe() $$,
  '42501',
  null,
  'anon cannot finalize a cloud wipe'
);

select * from finish();
rollback;
