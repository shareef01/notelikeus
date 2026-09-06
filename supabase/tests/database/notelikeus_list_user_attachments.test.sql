begin;
select plan(2);

select tests.create_supabase_user('attach_list@notelikeus.test');
select tests.authenticate_as('attach_list@notelikeus.test');
select public.apply_note_change(
  '9', 9::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

select lives_ok(
  $$ select public.register_note_attachment(
        'att-a',
        '9',
        public.expected_attachment_object_key(
          tests.get_supabase_uid('attach_list@notelikeus.test'),
          '9',
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
