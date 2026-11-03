begin;
select plan(19);

-- AUD-004: an account holds at most 10 000 notes and 100 MiB of note text. Getting a real account to
-- either limit would take minutes here, so the running total is set close to it directly, which is
-- exactly the state the trigger then has to judge.

create function tests.set_note_usage(p_identifier text, p_count integer, p_bytes bigint)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  update public.account_note_usage
  set note_count = p_count, note_bytes = p_bytes
  where owner_id = tests.get_supabase_uid(p_identifier);
end;
$$;

create function tests.recorded_usage(p_identifier text)
returns table (note_count integer, note_bytes bigint)
language sql
security definer
set search_path = public
as $$
  select u.note_count, u.note_bytes from public.account_note_usage u
  where u.owner_id = tests.get_supabase_uid(p_identifier);
$$;

-- What the account really holds, recounted from its rows.
create function tests.actual_usage(p_identifier text)
returns table (note_count integer, note_bytes bigint)
language sql
security definer
set search_path = public
as $$
  select count(*)::integer, coalesce(sum(notelikeus_internal.note_row_bytes(n)), 0)::bigint
  from public.notes n
  where n.owner_id = tests.get_supabase_uid(p_identifier);
$$;

create function tests.write_note(p_id text, p_content text)
returns jsonb
language sql
as $$
  select public.apply_note_change(
    p_id, p_id::bigint,
    (select revision from public.notes where note_id = p_id and owner_id = auth.uid()),
    'Title', p_content, 100::bigint, 0, false, false, false, 0, null::bigint,
    '[]'::jsonb, '[]'::jsonb
  );
$$;

select tests.create_supabase_user('limits_a@notelikeus.test');
select tests.create_supabase_user('limits_b@notelikeus.test');
select tests.authenticate_as('limits_a@notelikeus.test');

-- ------------------------------------------------------------------- the running total is exact

select tests.write_note('1', 'one');
select tests.write_note('2', 'two');
select results_eq(
  $$ select * from tests.recorded_usage('limits_a@notelikeus.test') $$,
  $$ select * from tests.actual_usage('limits_a@notelikeus.test') $$,
  'creating notes counts them and their text'
);
select tests.write_note('1', repeat('longer ', 50));
select results_eq(
  $$ select * from tests.recorded_usage('limits_a@notelikeus.test') $$,
  $$ select * from tests.actual_usage('limits_a@notelikeus.test') $$,
  'editing a note moves the total by exactly the change'
);
select public.apply_note_delete('2', (select revision from public.notes where note_id = '2'));
select results_eq(
  $$ select * from tests.recorded_usage('limits_a@notelikeus.test') $$,
  $$ select * from tests.actual_usage('limits_a@notelikeus.test') $$,
  'deleting a note takes it out of the total'
);

-- ------------------------------------------------------------------------------ the note count

select tests.set_note_usage('limits_a@notelikeus.test', 10000, 0);
select throws_ok(
  $$ select tests.write_note('3', 'one too many') $$,
  'NL005', null, 'a new note past 10 000 is refused'
);
select is(
  (select count(*)::integer from public.notes where note_id = '3'), 0, 'and is not created'
);
select is(
  (select note_count from tests.recorded_usage('limits_a@notelikeus.test')), 10000,
  'and the total is unchanged'
);
select is(
  tests.write_note('1', 'still editable')->>'status', 'applied',
  'an account at the count limit can still edit its notes'
);
select public.apply_note_delete('1', (select revision from public.notes where note_id = '1'));
select is(
  (select note_count from tests.recorded_usage('limits_a@notelikeus.test')), 9999,
  'an account at the limit can delete, and the delete comes off the total'
);
select tests.set_note_usage('limits_a@notelikeus.test', 10000, 0);
select throws_ok(
  $$ select public.restore_note(
       '1', 1::bigint, null::bigint, 'Title', 'back', 101::bigint, 0, false, false, false, 0,
       null::bigint, '[]'::jsonb, '[]'::jsonb) $$,
  'NL005', null, 'restoring a deleted note counts as adding one'
);

-- ------------------------------------------------------------------------------ the byte limit

select tests.set_note_usage('limits_a@notelikeus.test', 0, 0);
select tests.write_note('4', 'small');
select tests.set_note_usage(
  'limits_a@notelikeus.test', 1,
  notelikeus_internal.note_limit_bytes() - 10
);
select throws_ok(
  $$ select tests.write_note('4', repeat('x', 1000)) $$,
  'NL005', null, 'an edit that grows the account past 100 MiB is refused'
);
select is(
  (select content from public.notes where note_id = '4'), 'small', 'and the note keeps its old text'
);
select is(
  tests.write_note('4', '')->>'status', 'applied',
  'shrinking a note is always allowed, even at the limit'
);
select tests.set_note_usage('limits_a@notelikeus.test', 1, notelikeus_internal.note_limit_bytes() + 5000);
select is(
  public.apply_note_delete('4', (select revision from public.notes where note_id = '4'))->>'status',
  'applied',
  'an account over the limit can still delete'
);
select is(
  (select note_count from tests.recorded_usage('limits_a@notelikeus.test')), 0,
  'and its count comes down'
);

-- --------------------------------------------------------------------- isolation and the wipe

select tests.authenticate_as('limits_b@notelikeus.test');
select is(
  tests.write_note('1', 'another account')->>'status', 'applied',
  'one account at its limit does not limit another'
);
select throws_ok(
  $$ select * from public.account_note_usage $$,
  '42501', null, 'no user can read the running totals'
);
select throws_ok(
  format(
    'update public.account_note_usage set note_count = 0 where owner_id = %L',
    tests.get_supabase_uid('limits_b@notelikeus.test')
  ),
  '42501', null, 'nor reset their own'
);
select public.delete_all_user_cloud_data();
select results_eq(
  $$ select * from tests.recorded_usage('limits_b@notelikeus.test') $$,
  $$ values (0, 0::bigint) $$,
  'a cloud wipe brings the total back to zero'
);
select ok(
  (select tgenabled <> 'D' from pg_trigger where tgname = 'notes_account_usage'),
  'the limit trigger is installed and enabled'
);

select * from finish();
rollback;
