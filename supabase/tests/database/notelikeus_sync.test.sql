begin;
select plan(13);

select has_sequence('public', 'sync_revision_seq', 'sync revision sequence exists');
select has_table('public', 'notes', 'notes table exists');
select has_table('public', 'note_tombstones', 'tombstones table exists');
select has_table('public', 'sync_meta', 'sync_meta table exists');

select tests.rls_enabled('public', 'notes');
select tests.rls_enabled('public', 'note_tombstones');
select tests.rls_enabled('public', 'sync_meta');

select has_function('public', 'apply_note_change', ARRAY[
  'text', 'bigint', 'bigint', 'text', 'text', 'bigint', 'integer',
  'boolean', 'boolean', 'boolean', 'integer', 'bigint', 'jsonb', 'jsonb'
]);
select has_function('public', 'apply_note_delete', ARRAY['text', 'bigint']);
select has_function('public', 'pull_changes', ARRAY['bigint', 'integer']);
select has_function('public', 'fetch_full_snapshot', ARRAY[]::text[]);

select tests.create_supabase_user('user_a@notelikeus.test');

select tests.authenticate_as('user_a@notelikeus.test');
select results_eq(
  $$ select (public.apply_note_change(
      '42'::text, 42::bigint, null::bigint,
      'Hello'::text, 'Body'::text, 1000::bigint, 4278190080::integer,
      false, false, false, 0::integer, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    )->>'status') $$,
  ARRAY['applied'],
  'apply_note_change creates a note'
);

select results_eq(
  $$ select count(*)::bigint from public.notes where note_id = '42' $$,
  ARRAY[1::bigint],
  'note row exists'
);

select * from finish();
rollback;
