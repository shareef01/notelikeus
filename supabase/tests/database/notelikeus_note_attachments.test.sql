begin;
select plan(6);

select tests.create_supabase_user('attach_a@notelikeus.test');
select tests.create_supabase_user('attach_b@notelikeus.test');

select tests.authenticate_as('attach_a@notelikeus.test');
select results_eq(
  $$ select (public.register_note_attachment(
        'att-1',
        'note-1',
        public.expected_attachment_object_key(
          tests.get_supabase_uid('attach_a@notelikeus.test'),
          'note-1',
          'att-1'
        ),
        'image/png',
        42,
        'image'
      )->>'attachment_id') $$,
  ARRAY['att-1'::text],
  'user A can register an attachment in their namespace'
);

select tests.authenticate_as('attach_b@notelikeus.test');
select throws_ok(
  $$ select public.register_note_attachment(
        'att-1',
        'note-1',
        public.expected_attachment_object_key(
          tests.get_supabase_uid('attach_a@notelikeus.test'),
          'note-1',
          'att-1'
        ),
        'image/png',
        42,
        'image'
      ) $$,
  '22023',
  null,
  'user B cannot register attachment under user A object key'
);

select tests.authenticate_as('attach_a@notelikeus.test');
select results_eq(
  $$ select jsonb_array_length(public.list_note_attachments('note-1')) $$,
  ARRAY[1],
  'user A sees registered attachment'
);

select tests.authenticate_as('attach_b@notelikeus.test');
select results_eq(
  $$ select jsonb_array_length(public.list_note_attachments('note-1')) $$,
  ARRAY[0],
  'user B cannot list user A attachments'
);

select tests.authenticate_as('attach_a@notelikeus.test');
select results_eq(
  $$ select (public.delete_note_attachment('att-1', 'note-1')->>'deleted')::boolean $$,
  ARRAY[true],
  'user A can soft-delete attachment'
);

select tests.clear_authentication();
select throws_ok(
  $$ select public.register_note_attachment('att-2', 'note-1', 'owners/x/notes/note-1/att-2', 'image/png', 1) $$,
  -- 42501, not 28000: since 20250904000000 anon has no EXECUTE grant, so PostgREST is
  -- refused before the function body's own 'not authenticated' check can run.
  '42501',
  null,
  'anonymous cannot register attachments'
);

select * from finish();
rollback;
