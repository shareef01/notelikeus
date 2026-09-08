begin;
select plan(19);

-- A claimed attachment deletion retires that attachment identity for good.
--
-- 20260908120000 made restore_note respect the claim but left the other way back to live open:
-- finalize_note_attachment_put ends in INSERT ... ON CONFLICT DO UPDATE SET deleted_at = NULL,
-- which revived a claimed row and produced a live attachment whose bytes were already destroyed.
-- Replacement content uses a NEW attachment id, as the architecture documents; the old identity
-- stays dead.

create function tests.authenticate_as_service_role()
returns void
language plpgsql
as $$
begin
  perform set_config('role', 'service_role', true);
  perform set_config('request.jwt.claim.role', 'service_role', true);
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
end;
$$;

/** Forces a claim column on directly, bypassing the RPCs, to test the constraint itself. */
create function tests.force_claim(p_attachment_id text, p_column text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  execute format(
    'update public.note_attachments set %I = timezone(''utc'', now()) where attachment_id = $1',
    p_column
  ) using p_attachment_id;
  perform public.end_sync_mutation();
end;
$$;

/** Clears deleted_at directly, which the constraint must refuse while a claim stands. */
create function tests.force_claim_live(p_attachment_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  update public.note_attachments
  set deleted_at = null
  where attachment_id = p_attachment_id;
  perform public.end_sync_mutation();
end;
$$;

select tests.create_supabase_user('term_a@notelikeus.test');
select tests.create_supabase_user('term_b@notelikeus.test');
select tests.authenticate_as('term_a@notelikeus.test');

select public.apply_note_change(
  '910', 910::bigint, null::bigint,
  'Terminal', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '910', 'attT',
  public.expected_attachment_object_key(auth.uid(), '910', 'attT'),
  'image/png', 1024
);

-- 1. Claimed but not yet confirmed: the identity is already retired.
select public.begin_note_attachment_delete('910', 'attT');
select results_eq(
  $$ select (public.precheck_note_attachment_put('910', 'attT', 'image/png', null)->>'allowed')::boolean $$,
  ARRAY[false],
  'precheck refuses an identity whose delete is claimed'
);
select results_eq(
  $$ select public.precheck_note_attachment_put('910', 'attT', 'image/png', null)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'precheck says why, so the Worker never reads the body'
);
select results_eq(
  $$ select public.authorize_note_attachment_put('910', 'attT', 'image/png', 10)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'authorization refuses a claimed identity too'
);
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '910', 'attT',
       public.expected_attachment_object_key(auth.uid(), '910', 'attT'),
       'image/png', 10)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'finalization refuses a claimed identity -- the authoritative gate'
);
-- Refused as a value, not an exception: only an unambiguous answer lets the Worker delete the
-- bytes it just wrote.
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '910', 'attT',
       public.expected_attachment_object_key(auth.uid(), '910', 'attT'),
       'image/png', 10)->>'object_key' $$,
  ARRAY[(select public.expected_attachment_object_key(
    tests.get_supabase_uid('term_a@notelikeus.test'), '910', 'attT'))],
  'the refusal names the object key whose bytes are now nobody''s'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'attT' and deleted_at is null $$,
  ARRAY[0],
  'the refused finalization left the row deleted'
);

-- 2. Confirmed deletion stays terminal.
select public.finalize_note_attachment_delete('910', 'attT');
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '910', 'attT',
       public.expected_attachment_object_key(auth.uid(), '910', 'attT'),
       'image/png', 10)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'a confirmed delete cannot be finalized live again'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'attT'
       and deleted_at is null
       and (delete_claimed_at is not null or object_deleted_at is not null) $$,
  ARRAY[0],
  'no live row ever carries a terminal delete stamp'
);

-- 3. A NEW attachment id is unaffected: this is how replacement content is uploaded.
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '910', 'attU',
       public.expected_attachment_object_key(auth.uid(), '910', 'attU'),
       'image/png', 10)->>'attachment_id' $$,
  ARRAY['attU'::text],
  'a new attachment id still uploads normally'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'attU' and deleted_at is null $$,
  ARRAY[1],
  'the replacement identity is live'
);

-- 4. The sweeper's own claim retires an identity just as the owner's does.
select public.finalize_note_attachment_put(
  '910', 'attV',
  public.expected_attachment_object_key(auth.uid(), '910', 'attV'),
  'image/png', 10
);
select public.begin_note_attachment_delete('910', 'attV');
select tests.force_claim('attV', 'purge_claimed_at');
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '910', 'attV',
       public.expected_attachment_object_key(auth.uid(), '910', 'attV'),
       'image/png', 10)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'a sweeper-claimed identity cannot be finalized live either'
);

-- 5. The CHECK constraint is the structural backstop: no function, present or future, can leave
--    a live row carrying a claim. register_note_attachment still has the reviving ON CONFLICT and
--    is only out of clients' reach because its grants were revoked.
select throws_ok(
  $$ select tests.force_claim_live('attT') $$,
  '23514',
  null,
  'a direct write cannot clear deleted_at while a delete claim stands'
);

-- 6. Cross-user behaviour is unchanged: retiring an id in one namespace touches no other.
select tests.authenticate_as('term_b@notelikeus.test');
select public.apply_note_change(
  '911', 911::bigint, null::bigint,
  'Other owner', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '911', 'attT',
       public.expected_attachment_object_key(auth.uid(), '911', 'attT'),
       'image/png', 10)->>'attachment_id' $$,
  ARRAY['attT'::text],
  'another owner may use the same attachment id in their own namespace'
);
select results_eq(
  $$ select (public.precheck_note_attachment_put('911', 'attT', 'image/png', null)->>'allowed')::boolean $$,
  ARRAY[true],
  'the other owner identity is not retired by the first owner deletion'
);

-- 7. Attachments deleted only as a side effect of deleting the note carry no claim, so a restore
--    still brings them back and a re-upload of the same id is still allowed.
select tests.authenticate_as('term_a@notelikeus.test');
select public.finalize_note_attachment_put(
  '910', 'attW',
  public.expected_attachment_object_key(auth.uid(), '910', 'attW'),
  'image/png', 10
);
select public.apply_note_delete('910', (select revision from public.notes where note_id = '910'));
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'attW' and deleted_at is not null and delete_claimed_at is null $$,
  ARRAY[1],
  'deleting the note deletes its attachment without claiming it'
);
select public.restore_note(
  '910', 910::bigint, null::bigint,
  'Terminal', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'attW' and deleted_at is null $$,
  ARRAY[1],
  'restore still brings back an unclaimed attachment'
);
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where attachment_id = 'attT' and deleted_at is null $$,
  ARRAY[0],
  'and still leaves the retired identity dead'
);

-- 8. Nothing in the schema can produce the forbidden shape.
select results_eq(
  $$ select count(*)::integer from public.note_attachments
     where deleted_at is null
       and (delete_claimed_at is not null or purge_claimed_at is not null) $$,
  ARRAY[0],
  'no live attachment anywhere carries a deletion claim'
);
select isnt_empty(
  $$ select 1 from pg_constraint
     where conname = 'note_attachments_claimed_delete_is_terminal'
       and conrelid = 'public.note_attachments'::regclass $$,
  'the terminal-delete invariant is enforced by a table constraint, not only by convention'
);

select * from finish();
rollback;
