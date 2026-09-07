begin;
select plan(12);

-- Preflight has to tell the Worker whether an attachment is already committed, so a retried PUT
-- is idempotent instead of overwriting a live object it might then have to compensate away.

select tests.create_supabase_user('idem_user_a@notelikeus.test');
select tests.create_supabase_user('idem_user_b@notelikeus.test');

select tests.authenticate_as('idem_user_a@notelikeus.test');

select public.apply_note_change(
  '20', 20::bigint, null::bigint,
  'Note 20', 'Body 20', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

-- 1. Before anything is committed, the attachment is not live.
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 1024)->>'allowed')::boolean $$,
  ARRAY[true],
  'preflight allows a fresh attachment'
);
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 1024)->>'already_live')::boolean $$,
  ARRAY[false],
  'fresh attachment is not reported as already live'
);

-- 2. Commit it.
select results_eq(
  $$ select (public.finalize_note_attachment_put(
       '20', 'att20',
       public.expected_attachment_object_key(auth.uid(), '20', 'att20'),
       'image/png', 1024
     )->>'attachment_id') $$,
  ARRAY['att20'::text],
  'finalize commits the attachment'
);

-- 3. A repeat preflight for the same identity now reports the committed state.
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 2048)->>'already_live')::boolean $$,
  ARRAY[true],
  'committed attachment is reported as already live'
);
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 2048)->>'allowed')::boolean $$,
  ARRAY[true],
  'retry of a committed attachment is still allowed'
);
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 2048)->>'size_bytes')::bigint $$,
  ARRAY[1024::bigint],
  'preflight reports the committed size, not the retried one'
);
select results_eq(
  $$ select public.authorize_note_attachment_put('20', 'att20', 'image/png', 2048)->>'mime_type' $$,
  ARRAY['image/png'::text],
  'preflight reports the committed mime type'
);
select results_eq(
  $$ select public.authorize_note_attachment_put('20', 'att20', 'image/png', 2048)->>'object_key' $$,
  ARRAY[(select public.expected_attachment_object_key(auth.uid(), '20', 'att20'))],
  'preflight still returns the canonical owner-scoped key'
);

-- 4. A different attachment id on the same note stays fresh: replacing content uses a new id.
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att21', 'image/png', 1024)->>'already_live')::boolean $$,
  ARRAY[false],
  'a different attachment id is not already live'
);

-- 5. Deleting the attachment clears the live state, so a later upload is fresh again.
select public.finalize_note_attachment_delete('20', 'att20');
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 1024)->>'already_live')::boolean $$,
  ARRAY[false],
  'a deleted attachment is no longer already live'
);

-- 6. Ownership still gates everything: another account learns nothing about this attachment.
select tests.authenticate_as('idem_user_b@notelikeus.test');
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 1024)->>'allowed')::boolean $$,
  ARRAY[false],
  'another owner cannot preflight this note'
);

-- 7. Tombstoned notes remain refused regardless of committed attachments.
select tests.authenticate_as('idem_user_a@notelikeus.test');
select public.apply_note_delete('20', (select revision from public.notes where note_id = '20'));
select results_eq(
  $$ select (public.authorize_note_attachment_put('20', 'att20', 'image/png', 1024)->>'allowed')::boolean $$,
  ARRAY[false],
  'a tombstoned note refuses attachment preflight'
);

select * from finish();
rollback;
