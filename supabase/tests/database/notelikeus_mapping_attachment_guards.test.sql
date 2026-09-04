-- Regression tests for 20250904000000_mapping_and_attachment_guards.sql.
--
-- Before that migration, `firebase_uid_mappings` and `note_attachments` carried permissive RLS
-- write policies and no mutation guard, so an authenticated PostgREST client could write them
-- directly and skip the invariants that only exist inside their RPCs. Each attack below is one
-- that actually succeeded against the pre-migration schema.

begin;
select plan(12);

select tests.create_supabase_user('guard_a@notelikeus.test');
select tests.create_supabase_user('guard_b@notelikeus.test');

-- ---------------------------------------------------------------------------
-- firebase_uid_mappings: the RPC is the only way in.
-- ---------------------------------------------------------------------------
select tests.authenticate_as('guard_b@notelikeus.test');

select throws_ok(
  $$ insert into public.firebase_uid_mappings (firebase_uid, owner_id)
     values ('victim_firebase_uid', tests.get_supabase_uid('guard_b@notelikeus.test')) $$,
  '42501',
  null,
  'direct insert into firebase_uid_mappings is blocked'
);
select is(
  public.link_firebase_uid('guard_b_uid')->>'firebase_uid',
  'guard_b_uid',
  'link_firebase_uid still writes the mapping through the guard'
);
-- UPDATE/DELETE need a row to match, or the BEFORE ROW trigger never fires and the assertion
-- would pass for the wrong reason. The mapping above is that row.
select throws_ok(
  $$ update public.firebase_uid_mappings set firebase_uid = 'other' $$,
  '42501',
  null,
  'direct update of firebase_uid_mappings is blocked'
);
select throws_ok(
  $$ delete from public.firebase_uid_mappings $$,
  '42501',
  null,
  'direct delete of firebase_uid_mappings is blocked'
);
select is(
  public.link_firebase_uid('guard_b_uid_relinked')->>'firebase_uid',
  'guard_b_uid_relinked',
  'relinking the caller''s own mapping still works'
);

-- ---------------------------------------------------------------------------
-- note_attachments: the object_key namespace check cannot be bypassed.
-- ---------------------------------------------------------------------------
select tests.authenticate_as('guard_a@notelikeus.test');
select lives_ok(
  $$ select public.register_note_attachment(
       'a_att', '1',
       public.expected_attachment_object_key(auth.uid(), '1', 'a_att'),
       'image/png', 10, 'image'
     ) $$,
  'register_note_attachment still writes through the guard'
);

select tests.authenticate_as('guard_b@notelikeus.test');
select throws_ok(
  $$ insert into public.note_attachments (attachment_id, owner_id, note_id, object_key)
     values (
       'squat',
       tests.get_supabase_uid('guard_b@notelikeus.test'),
       '1',
       'owners/' || tests.get_supabase_uid('guard_a@notelikeus.test')::text || '/notes/1/a_att_2'
     ) $$,
  '42501',
  null,
  'B cannot direct-insert metadata claiming an object key in A''s namespace'
);
select throws_ok(
  $$ select public.register_note_attachment(
       'a_att_2', '1',
       'owners/' || tests.get_supabase_uid('guard_a@notelikeus.test')::text || '/notes/1/a_att_2',
       'image/png', 10, 'image'
     ) $$,
  '22023',
  null,
  'B cannot register an object key outside their own namespace via the RPC'
);

-- A can still claim the key B tried to squat: the unique-key denial-of-upload is gone.
select tests.authenticate_as('guard_a@notelikeus.test');
select lives_ok(
  $$ select public.register_note_attachment(
       'a_att_2', '1',
       public.expected_attachment_object_key(auth.uid(), '1', 'a_att_2'),
       'image/png', 10, 'image'
     ) $$,
  'A can still register the object key B attempted to squat'
);
select is(
  (public.delete_note_attachment('a_att_2', '1')->>'deleted')::boolean,
  true,
  'delete_note_attachment still soft-deletes through the guard'
);

-- ---------------------------------------------------------------------------
-- Grants: anonymous callers are refused before the function body runs.
-- Supabase's ALTER DEFAULT PRIVILEGES grants anon EXECUTE explicitly, so the original
-- `REVOKE ... FROM PUBLIC` alone never removed it.
-- ---------------------------------------------------------------------------
select is(
  bool_or(has_function_privilege('anon', p.oid, 'EXECUTE')),
  false,
  'anon holds EXECUTE on no public schema function'
) from pg_proc p where p.pronamespace = 'public'::regnamespace;

select is(
  has_sequence_privilege('anon', 'public.sync_revision_seq', 'USAGE'),
  false,
  'anon cannot draw from the sync revision sequence'
);

select * from finish();
rollback;
