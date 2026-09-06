-- Regression tests for attachment mutation guards.
--
-- Authenticated PostgREST clients must not write note_attachments directly and skip the
-- invariants that only exist inside register_note_attachment / delete_note_attachment.

begin;
select plan(7);

select tests.create_supabase_user('guard_a@notelikeus.test');
select tests.create_supabase_user('guard_b@notelikeus.test');

-- ---------------------------------------------------------------------------
-- note_attachments: the object_key namespace check cannot be bypassed.
-- ---------------------------------------------------------------------------
select tests.authenticate_as('guard_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select lives_ok(
  $$ select public.finalize_note_attachment_put(
       '1', 'a_att',
       public.expected_attachment_object_key(auth.uid(), '1', 'a_att'),
       'image/png', 10, 'image'
     ) $$,
  'finalize_note_attachment_put still writes through the guard'
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
  $$ select public.finalize_note_attachment_put(
       '1', 'a_att_2',
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
  $$ select public.finalize_note_attachment_put(
       '1', 'a_att_2',
       public.expected_attachment_object_key(auth.uid(), '1', 'a_att_2'),
       'image/png', 10, 'image'
     ) $$,
  'A can still register the object key B attempted to squat'
);
select is(
  (public.finalize_note_attachment_delete('1', 'a_att_2')->>'allowed')::boolean,
  true,
  'finalize_note_attachment_delete still soft-deletes through the guard'
);

-- ---------------------------------------------------------------------------
-- Grants: anonymous callers are refused before the function body runs.
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
