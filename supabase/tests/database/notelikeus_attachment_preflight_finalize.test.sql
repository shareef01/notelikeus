begin;
select plan(21);

select tests.create_supabase_user('pf_user_a@notelikeus.test');
select tests.create_supabase_user('pf_user_b@notelikeus.test');

-- 1. Anonymous access is completely refused
select tests.clear_authentication();
select throws_ok(
  $$ select public.authorize_note_attachment_put('1', 'att1', 'image/png', 100) $$,
  '42501',
  null,
  'anon cannot call authorize_note_attachment_put'
);
select throws_ok(
  $$ select public.finalize_note_attachment_put('1', 'att1', 'owners/x/notes/1/att1', 'image/png', 100) $$,
  '42501',
  null,
  'anon cannot call finalize_note_attachment_put'
);
select throws_ok(
  $$ select public.authorize_note_attachment_delete('1', 'att1') $$,
  '42501',
  null,
  'anon cannot call authorize_note_attachment_delete'
);
select throws_ok(
  $$ select public.finalize_note_attachment_delete('1', 'att1') $$,
  '42501',
  null,
  'anon cannot call finalize_note_attachment_delete'
);

-- 2. Authenticated user cannot call internal register_note_attachment directly
select tests.authenticate_as('pf_user_a@notelikeus.test');
select throws_ok(
  $$ select public.register_note_attachment('att1', '1', 'owners/x/notes/1/att1', 'image/png', 100) $$,
  '42501',
  null,
  'authenticated user cannot call register_note_attachment'
);
select throws_ok(
  $$ select public.delete_note_attachment('att1', '1') $$,
  '42501',
  null,
  'authenticated user cannot call delete_note_attachment'
);

-- Setup a note for user A
select public.apply_note_change(
  '10', 10::bigint, null::bigint,
  'Note 10', 'Body 10', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

-- 3. Preflight PUT succeeds but creates NO live metadata row
select results_eq(
  $$ select (public.authorize_note_attachment_put('10', 'att1', 'image/png', 1024)->>'allowed')::boolean $$,
  ARRAY[true],
  'preflight PUT authorized for owned note'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments where note_id = '10' and attachment_id = 'att1' $$,
  ARRAY[0],
  'preflight PUT does NOT create attachment metadata'
);

-- 4. Preflight PUT rejections
select results_eq(
  $$ select (public.authorize_note_attachment_put('999', 'att1', 'image/png', 1024)->>'allowed')::boolean $$,
  ARRAY[false],
  'preflight PUT rejects nonexistent note'
);
select results_eq(
  $$ select (public.authorize_note_attachment_put('10', 'att1', 'application/javascript', 1024)->>'allowed')::boolean $$,
  ARRAY[false],
  'preflight PUT rejects disallowed MIME'
);
select results_eq(
  $$ select (public.authorize_note_attachment_put('10', 'att1', 'image/png', 10485761)->>'allowed')::boolean $$,
  ARRAY[false],
  'preflight PUT rejects oversize payload'
);

-- User B cannot preflight upload on User A note
select tests.authenticate_as('pf_user_b@notelikeus.test');
select results_eq(
  $$ select (public.authorize_note_attachment_put('10', 'att1', 'image/png', 1024)->>'allowed')::boolean $$,
  ARRAY[false],
  'preflight PUT rejects foreign note'
);

-- 5. Finalize PUT commits metadata
select tests.authenticate_as('pf_user_a@notelikeus.test');
select throws_ok(
  $$ select public.finalize_note_attachment_put(
       '10', 'att1',
       'owners/other-user/notes/10/att1',
       'image/png', 1024
     ) $$,
  '22023',
  null,
  'finalize PUT rejects mismatched object key'
);

select results_eq(
  $$ select (public.finalize_note_attachment_put(
       '10', 'att1',
       public.expected_attachment_object_key(auth.uid(), '10', 'att1'),
       'image/png', 1024
     )->>'attachment_id') $$,
  ARRAY['att1'::text],
  'finalize PUT successfully registers attachment'
);

select results_eq(
  $$ select count(*)::integer from public.note_attachments where note_id = '10' and attachment_id = 'att1' and deleted_at is null $$,
  ARRAY[1],
  'attachment metadata is now live after finalization'
);

-- 6. Preflight DELETE validates without marking deleted
select results_eq(
  $$ select (public.authorize_note_attachment_delete('10', 'att1')->>'allowed')::boolean $$,
  ARRAY[true],
  'preflight DELETE allowed for existing attachment'
);
select results_eq(
  $$ select (deleted_at is null) from public.note_attachments where note_id = '10' and attachment_id = 'att1' $$,
  ARRAY[true],
  'preflight DELETE does NOT mark deleted_at'
);

-- 7. Finalize DELETE marks deleted_at
select results_eq(
  $$ select (public.finalize_note_attachment_delete('10', 'att1')->>'allowed')::boolean $$,
  ARRAY[true],
  'finalize DELETE marks attachment deleted'
);
select results_eq(
  $$ select (deleted_at is not null) from public.note_attachments where note_id = '10' and attachment_id = 'att1' $$,
  ARRAY[true],
  'attachment deleted_at is now set'
);

-- Foreign user cannot authorize delete User A's attachment
select tests.authenticate_as('pf_user_b@notelikeus.test');
select results_eq(
  $$ select (public.authorize_note_attachment_delete('10', 'att1')->>'allowed')::boolean $$,
  ARRAY[false],
  'foreign user cannot authorize delete another user attachment'
);

-- User A sees the attachment still in database with deleted_at set
select tests.authenticate_as('pf_user_a@notelikeus.test');
select results_eq(
  $$ select count(*)::integer from public.note_attachments where owner_id = tests.get_supabase_uid('pf_user_a@notelikeus.test') and attachment_id = 'att1' and deleted_at is not null $$,
  ARRAY[1],
  'attachment still exists in database with deleted_at set'
);

select * from finish();
rollback;
