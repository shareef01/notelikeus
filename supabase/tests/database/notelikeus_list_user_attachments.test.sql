begin;
select plan(2);

select tests.create_supabase_user('attach_list@notelikeus.test');
select tests.authenticate_as('attach_list@notelikeus.test');

select lives_ok(
  $$ select public.register_note_attachment(
        'att-a',
        'note-9',
        public.expected_attachment_object_key(
          tests.get_supabase_uid('attach_list@notelikeus.test'),
          'note-9',
          'att-a'
        ),
        'image/png',
        10,
        'image'
      ) $$,
  'user can register an attachment'
);

select results_eq(
  $$ select jsonb_array_length(public.list_user_attachments()) $$,
  ARRAY[1],
  'list_user_attachments returns active attachments'
);

select * from finish();
rollback;
