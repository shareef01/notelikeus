begin;
select plan(12);

select tests.create_supabase_user('authz_a@notelikeus.test');
select tests.create_supabase_user('authz_b@notelikeus.test');

select tests.authenticate_as('authz_a@notelikeus.test');
select public.apply_note_change(
  '1', 1::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

select results_eq(
  $$ select (public.authorize_note_attachment_put('1', 'att1', 'image/png', 12)->>'allowed')::boolean $$,
  ARRAY[true],
  'valid note upload authorized'
);

select results_eq(
  $$ select (public.authorize_note_attachment_put('99', 'att1', 'image/png', 12)->>'allowed')::boolean $$,
  ARRAY[false],
  'fake note upload rejected'
);

select public.apply_note_change(
  '2', 2::bigint, null::bigint,
  't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_delete('2', (select revision from public.notes where note_id = '2'));
select results_eq(
  $$ select (public.authorize_note_attachment_put('2', 'att1', 'image/png', 12)->>'allowed')::boolean $$,
  ARRAY[false],
  'tombstoned note upload rejected'
);

select tests.authenticate_as('authz_b@notelikeus.test');
select results_eq(
  $$ select (public.authorize_note_attachment_put('1', 'att1', 'image/png', 12)->>'allowed')::boolean $$,
  ARRAY[false],
  'foreign note upload rejected'
);

select tests.authenticate_as('authz_a@notelikeus.test');
select throws_ok(
  $$ select public.register_note_attachment(
       'bad id', '1',
       public.expected_attachment_object_key(auth.uid(), '1', 'bad id'),
       'image/png', 12, 'image'
     ) $$,
  '22023',
  null,
  'arbitrary attachment id rejected'
);

select throws_ok(
  $$ select public.register_note_attachment(
       'att2', '1',
       public.expected_attachment_object_key(auth.uid(), '1', 'att2'),
       'text/html', 12, 'image'
     ) $$,
  '22023',
  null,
  'disallowed MIME rejected'
);

select throws_ok(
  $$ select public.register_note_attachment(
       'att3', '1',
       public.expected_attachment_object_key(auth.uid(), '1', 'att3'),
       'image/png', 10485761, 'image'
     ) $$,
  '22023',
  null,
  'oversize metadata rejected'
);

select throws_ok(
  $$ select public.register_note_attachment(
       'att4', '1',
       public.expected_attachment_object_key(auth.uid(), '1', 'att4'),
       'image/png', 12, 'video'
     ) $$,
  '22023',
  null,
  'disallowed attachment type rejected'
);

select results_eq(
  $$ select (public.authorize_note_attachment_get('1', 'att1')->>'allowed')::boolean $$,
  ARRAY[true],
  'valid GET authorized after registration'
);

select results_eq(
  $$ select (public.authorize_note_attachment_get('1', 'missing')->>'allowed')::boolean $$,
  ARRAY[false],
  'invalid metadata GET rejected'
);

select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));
select results_eq(
  $$ select (deleted_at IS NOT NULL) from public.note_attachments
     where owner_id = tests.get_supabase_uid('authz_a@notelikeus.test') and attachment_id = 'att1' $$,
  ARRAY[true],
  'authoritative note delete marks attachment metadata pending-deleted'
);

select results_eq(
  $$ select (public.authorize_note_attachment_delete('1', 'att1')->>'allowed')::boolean $$,
  ARRAY[true],
  'cleanup delete is idempotent'
);

select * from finish();
rollback;
