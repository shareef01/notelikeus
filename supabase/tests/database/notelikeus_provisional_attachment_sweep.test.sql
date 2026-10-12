-- F-09: claim_orphaned_provisional_attachments reclaims provisional uploads that no note commit will
-- ever promote -- and nothing else. The lanes below cover each branch of the rule, the rows it must
-- leave alone (above all an upload still waiting for its note commit), and the hand-off to the
-- existing unconfirmed-delete sweep.

begin;
select plan(14);

-- Moves a row's clocks into the past. Definer, and inside the sync-mutation guard, because both
-- tables refuse direct writes otherwise.
create function tests.backdate_provisional(p_attachment_id text, p_days integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  update public.note_attachments
  set created_at = timezone('utc', now()) - make_interval(days => p_days)
  where attachment_id = p_attachment_id;
  perform public.end_sync_mutation();
end;
$$;

create function tests.backdate_note_commit(p_note_id text, p_days integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  update public.notes
  set server_updated_at = timezone('utc', now()) - make_interval(days => p_days)
  where note_id = p_note_id;
  perform public.end_sync_mutation();
end;
$$;

create function tests.backdate_delete_claims(p_hours integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.begin_sync_mutation();
  update public.note_attachments
  set delete_claimed_at = timezone('utc', now()) - make_interval(hours => p_hours)
  where delete_claimed_at is not null;
  perform public.end_sync_mutation();
end;
$$;

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

create function tests.upload_provisional(p_note_id text, p_attachment_id text)
returns void
language sql
as $$
  select public.finalize_note_attachment_put(
    p_note_id, p_attachment_id,
    public.expected_attachment_object_key(auth.uid(), p_note_id, p_attachment_id),
    'image/png', 1000, true, 'image'
  );
$$;

create function tests.commit_note(p_note_id text, p_attachment_ids jsonb)
returns void
language sql
as $$
  select public.apply_note_change(
    p_note_id, p_note_id::bigint,
    (select revision from public.notes where note_id = p_note_id),
    't', 'b', 100::bigint, 0, false, false, false, 0, null::bigint,
    '[]'::jsonb, '[]'::jsonb, p_attachment_ids
  );
$$;

select tests.create_supabase_user('prov_a@notelikeus.test');
select tests.authenticate_as('prov_a@notelikeus.test');

-- Notes 1..6, each committed once before its upload.
select tests.commit_note(n::text, null) from generate_series(1, 6) as n;

-- 1: an upload still waiting for its note commit. Old, but nothing says it will not be committed.
select tests.upload_provisional('1', 'pending');
-- 2: the note was committed again after the upload without naming it, long ago: a stale orphan.
select tests.upload_provisional('2', 'stale');
select tests.commit_note('2', '[]'::jsonb);
-- 3: the same, but that later commit is recent: a refused commit may still be retried.
select tests.upload_provisional('3', 'recent-commit');
select tests.commit_note('3', '[]'::jsonb);
-- 4: an unnaming commit, but the upload itself is inside the grace period.
select tests.upload_provisional('4', 'young');
select tests.commit_note('4', '[]'::jsonb);
-- 5: a committed attachment whose note was committed again without naming it: not provisional, so
-- never this sweep's business.
select tests.upload_provisional('5', 'committed');
select tests.commit_note('5', '["committed"]'::jsonb);
select tests.commit_note('5', '[]'::jsonb);
-- 6: a provisional row on a deleted note: the note delete already marked it deleted, so it belongs
-- to the orphan sweep, not to this one.
select tests.upload_provisional('6', 'on-deleted');
select public.apply_note_delete('6', (select revision from public.notes where note_id = '6'));

select tests.clear_authentication();
select tests.backdate_note_commit('1', 40);
select tests.backdate_provisional('pending', 30);
select tests.backdate_provisional('stale', 40);
select tests.backdate_note_commit('2', 30);
select tests.backdate_provisional('recent-commit', 40);
select tests.backdate_note_commit('3', 2);
select tests.backdate_provisional('young', 10);
select tests.backdate_note_commit('4', 9);
select tests.backdate_provisional('committed', 40);
select tests.backdate_note_commit('5', 30);
select tests.backdate_provisional('on-deleted', 40);

select tests.authenticate_as('prov_a@notelikeus.test');
select throws_ok(
  $$ select public.claim_orphaned_provisional_attachments() $$,
  '42501',
  null,
  'F09-1 an authenticated user cannot run the provisional sweep'
);

select tests.authenticate_as_service_role();
select is(
  (select array_agg(value->>'attachment_id' order by value->>'attachment_id')
     from jsonb_array_elements(public.claim_orphaned_provisional_attachments()) as value),
  array['stale'],
  'F09-2 only a provisional row proven orphaned, past the grace period, is claimed'
);

-- Read as service_role: anon (clear_authentication) sees no rows through RLS.
select tests.authenticate_as_service_role();
select is(
  (select count(*)::int from public.note_attachments
    where attachment_id = 'stale'
      and deleted_at is not null and delete_claimed_at is not null),
  1,
  'F09-3 a claimed row is marked deleted and delete-claimed, like an owner DELETE'
);
select ok(
  (select deleted_at is null and committed_at is null
     from public.note_attachments where attachment_id = 'pending'),
  'F09-4 an upload still waiting for its note commit is left alone'
);
select ok(
  (select deleted_at is null from public.note_attachments where attachment_id = 'recent-commit'),
  'F09-5 a row whose unnaming commit is recent is left alone'
);
select ok(
  (select deleted_at is null from public.note_attachments where attachment_id = 'young'),
  'F09-6 a row inside the grace period is left alone'
);
select ok(
  (select deleted_at is null and committed_at is not null
     from public.note_attachments where attachment_id = 'committed'),
  'F09-7 a committed row is never claimed by this sweep'
);
select ok(
  (select delete_claimed_at is null from public.note_attachments where attachment_id = 'on-deleted'),
  'F09-7b a deleted note''s rows are left to the orphan sweep'
);

select tests.authenticate_as_service_role();
select is(
  public.claim_orphaned_provisional_attachments(),
  '[]'::jsonb,
  'F09-8 a second run claims nothing more'
);

-- The claim is terminal: neither a re-upload nor a note commit naming the id revives it.
select tests.authenticate_as('prov_a@notelikeus.test');
select is(
  public.finalize_note_attachment_put(
    '2', 'stale', public.expected_attachment_object_key(auth.uid(), '2', 'stale'),
    'image/png', 1000, true, 'image'
  )->>'reason',
  'terminally_deleted',
  'F09-9 a claimed identity cannot be re-uploaded'
);
select tests.commit_note('2', '["stale"]'::jsonb);
-- Read as service_role: anon (clear_authentication) sees no rows through RLS.
select tests.authenticate_as_service_role();
select ok(
  (select deleted_at is not null and committed_at is null
     from public.note_attachments where attachment_id = 'stale'),
  'F09-10 a note commit naming a claimed id does not promote it'
);

select tests.authenticate_as('prov_a@notelikeus.test');
select is(
  (select count(*)::int from jsonb_array_elements(public.list_user_attachments()) as value
    where value->>'attachment_id' = 'stale'),
  0,
  'F09-11 a claimed row never hydrates'
);

-- The bytes go through the existing unconfirmed-delete sweep, after its one-hour delay.
select tests.clear_authentication();
select tests.backdate_delete_claims(2);
select tests.authenticate_as_service_role();
select is(
  (select array_agg(value->>'attachment_id' order by value->>'attachment_id')
     from jsonb_array_elements(public.list_unconfirmed_attachment_deletes()) as value),
  array['stale'],
  'F09-12 a claimed row is handed to the unconfirmed-delete sweep'
);

-- Quota: the claimed rows no longer count against the owner's total.
-- Read as service_role: anon (clear_authentication) sees no rows through RLS.
select tests.authenticate_as_service_role();
select is(
  (select coalesce(sum(size_bytes), 0)::bigint from public.note_attachments
    where owner_id = tests.get_supabase_uid('prov_a@notelikeus.test') and deleted_at is null),
  4000::bigint,
  'F09-13 a claimed row stops counting against the quota (pending, recent-commit, young, committed remain)'
);

select * from finish();
rollback;
