begin;
select plan(16);

-- AUD-008: the snapshot can be read a page at a time, and every page reports the account's state so a
-- client can tell a consistent set of pages from one an intervening write split.

create function tests.write_note(p_id text, p_content text)
returns jsonb
language sql
as $$
  select public.apply_note_change(
    p_id, p_id::bigint,
    (select revision from public.notes where note_id = p_id and owner_id = auth.uid()),
    'Title ' || p_id, p_content, 100::bigint, 0, false, false, false, p_id::integer, null::bigint,
    '[]'::jsonb, '[]'::jsonb
  );
$$;

-- Every page of one kind, following next_after, as one array; the states every page reported.
create function tests.all_pages(p_kind text, p_limit integer)
returns table (items jsonb, states jsonb, pages integer)
language plpgsql
as $$
declare
  v_after text := null;
  v_page jsonb;
begin
  items := '[]'::jsonb;
  states := '[]'::jsonb;
  pages := 0;
  loop
    v_page := public.fetch_snapshot_page(p_kind, v_after, p_limit);
    items := items || (v_page->'items');
    states := states || jsonb_build_array(v_page->'state');
    pages := pages + 1;
    v_after := v_page->>'next_after';
    exit when v_after is null or pages > 1000;
  end loop;
  return next;
end;
$$;

select tests.create_supabase_user('paged_a@notelikeus.test');
select tests.create_supabase_user('paged_b@notelikeus.test');

select tests.authenticate_as('paged_b@notelikeus.test');
select tests.write_note('99', 'not yours');

select tests.authenticate_as('paged_a@notelikeus.test');
select tests.write_note(i::text, 'body ' || i) from generate_series(1, 7) as i;
select public.apply_note_delete('6', (select revision from public.notes where note_id = '6'));
select public.apply_note_delete('7', (select revision from public.notes where note_id = '7'));

-- ------------------------------------------------------------------ the pages make up the snapshot

select is(
  (select pages from tests.all_pages('notes', 2)), 3, 'five notes in pages of two take three pages'
);
select is(
  (select (select array_agg(e->>'note_id' order by e->>'note_id') from jsonb_array_elements(items) e)
   from tests.all_pages('notes', 2)),
  (select array_agg(e->>'note_id' order by e->>'note_id')
   from jsonb_array_elements(public.fetch_full_snapshot()->'notes') e),
  'every note appears exactly once, and they are the full snapshot''s notes'
);
select is(
  (select items from tests.all_pages('notes', 2)),
  (select jsonb_agg(e order by e->>'note_id') from jsonb_array_elements(public.fetch_full_snapshot()->'notes') e),
  'each row is exactly what the full snapshot returns for it'
);
select is(
  (select items from tests.all_pages('tombstones', 1)),
  (select jsonb_agg(e order by e->>'note_id') from jsonb_array_elements(public.fetch_full_snapshot()->'tombstones') e),
  'the tombstone pages make up the full snapshot''s tombstones'
);
select ok(
  not exists (
    select 1 from jsonb_array_elements((select items from tests.all_pages('notes', 500))) e
    where e->>'note_id' = '99'
  ),
  'another account''s notes never appear'
);
select is(
  public.fetch_snapshot_page('notes', null, 500)->'next_after', 'null'::jsonb,
  'a page that holds everything says there is no more'
);

-- ----------------------------------------------------------------------------- the state

select is(
  (select count(distinct s)::integer from jsonb_array_elements((select states from tests.all_pages('notes', 1))) s),
  1,
  'while nothing changes, every page reports the same state'
);
select is(
  public.fetch_snapshot_page('notes', null, 1)->'state',
  jsonb_build_object(
    'head_revision', (select greatest(
      (select max(revision) from public.notes where owner_id = auth.uid()),
      (select max(revision) from public.note_tombstones where owner_id = auth.uid()))),
    'wipe_generation', 0,
    'note_count', 5,
    'tombstone_count', 2
  ),
  'the state is the newest revision, the generation and both counts'
);

select set_config('tests.before_state', (public.fetch_snapshot_page('notes', null, 1)->'state')::text, true);
select tests.write_note('1', 'edited');
select isnt(
  public.fetch_snapshot_page('notes', null, 1)->'state', current_setting('tests.before_state')::jsonb,
  'an edit moves the state'
);
select set_config('tests.before_state', (public.fetch_snapshot_page('notes', null, 1)->'state')::text, true);
select public.clear_note_tombstone('7');
select isnt(
  public.fetch_snapshot_page('notes', null, 1)->'state', current_setting('tests.before_state')::jsonb,
  'clearing a tombstone, which writes no revision, still moves the state'
);
select set_config('tests.before_state', (public.fetch_snapshot_page('notes', null, 1)->'state')::text, true);
select public.apply_note_delete('2', (select revision from public.notes where note_id = '2'));
select isnt(
  public.fetch_snapshot_page('notes', null, 1)->'state', current_setting('tests.before_state')::jsonb,
  'a delete moves the state'
);

-- ------------------------------------------------------------------------- the byte budget

select tests.write_note((100 + i)::text, repeat('x', 99000)) from generate_series(1, 45) as i;
select cmp_ok(
  jsonb_array_length(public.fetch_snapshot_page('notes', '100', 500)->'items'), '<', 45,
  'a page stops at its byte budget before its row limit'
);
select is(
  (select jsonb_array_length(items) from tests.all_pages('notes', 500)),
  (public.fetch_full_snapshot()->>'note_count')::integer,
  'and the pages after it still add up to every note'
);

-- ------------------------------------------------------------------------- refusals

select throws_ok(
  $$ select public.fetch_snapshot_page('everything', null, 10) $$,
  '22023', null, 'only notes and tombstones can be paged'
);
select tests.clear_authentication();
select throws_ok(
  $$ select public.fetch_snapshot_page('notes', null, 10) $$,
  '42501', null, 'anon cannot read a page'
);
select tests.authenticate_as('paged_a@notelikeus.test');
select public.delete_all_user_cloud_data();
select is(
  (public.fetch_snapshot_page('notes', null, 10)->'state'->>'wipe_generation')::integer, 1,
  'a wipe moves the state through its generation'
);

select * from finish();
rollback;
