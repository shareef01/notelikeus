-- N-01 recovery: restore_stranded_provisional_attachments promotes the provisional uploads Android
-- 2.0.0 stranded, so they hydrate again, and leaves alone what is young, deleted or already
-- committed. Only the database owner can run it.

begin;
select plan(8);

-- Moves an upload's clock into the past. Definer, and inside the sync-mutation guard, because the
-- table refuses direct writes otherwise.
create function tests.backdate_upload(p_attachment_id text, p_days integer)
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

select tests.create_supabase_user('restore_a@notelikeus.test');
select tests.authenticate_as('restore_a@notelikeus.test');

select tests.commit_note(n::text, null) from generate_series(1, 5) as n;

-- 1: 2.0.0's shape: the note was pushed, the image uploaded afterwards, and nothing named it.
select tests.upload_provisional('1', 'stranded');
-- 2: the same, and the note was edited later without naming it.
select tests.upload_provisional('2', 'stranded-edited');
select tests.commit_note('2', '[]'::jsonb);
-- 3: an upload from today, which a current client is still about to commit.
select tests.upload_provisional('3', 'fresh');
-- 4: a provisional row on a deleted note, already marked deleted by the note delete.
select tests.upload_provisional('4', 'on-deleted');
select public.apply_note_delete('4', (select revision from public.notes where note_id = '4'));
-- 5: an attachment its note committed normally.
select tests.upload_provisional('5', 'committed');
select tests.commit_note('5', '["committed"]'::jsonb);

select tests.clear_authentication();
select tests.backdate_upload('stranded', 9);
select tests.backdate_upload('stranded-edited', 9);
select tests.backdate_upload('on-deleted', 9);
select tests.backdate_upload('committed', 9);

select tests.authenticate_as('restore_a@notelikeus.test');
select throws_ok(
  $$ select public.restore_stranded_provisional_attachments(now()) $$,
  '42501',
  null,
  'RSP-1 a signed-in user cannot run the restore'
);
select tests.clear_authentication();
select throws_ok(
  $$ select public.restore_stranded_provisional_attachments(now()) $$,
  '42501',
  null,
  'RSP-2 nor can anon'
);

reset role;
select is(
  public.restore_stranded_provisional_attachments(timezone('utc', now()) - interval '1 day'),
  2,
  'RSP-3 the owner restores exactly the two stranded uploads'
);
select ok(
  (select committed_at = created_at from public.note_attachments where attachment_id = 'stranded'),
  'RSP-4 a restored row is committed as of its upload'
);
select ok(
  (select committed_at is null from public.note_attachments where attachment_id = 'fresh'),
  'RSP-5 an upload from the last day stays provisional'
);
select ok(
  (select deleted_at is not null and committed_at is null
     from public.note_attachments where attachment_id = 'on-deleted'),
  'RSP-6 a deleted note''s row is not revived'
);

select tests.authenticate_as('restore_a@notelikeus.test');
select is(
  (select array_agg(value->>'attachment_id' order by value->>'attachment_id')
     from jsonb_array_elements(public.list_user_attachments()) as value),
  array['committed', 'stranded', 'stranded-edited'],
  'RSP-7 restored rows hydrate again; the fresh upload still does not'
);

reset role;
select is(
  public.restore_stranded_provisional_attachments(timezone('utc', now()) - interval '1 day'),
  0,
  'RSP-8 a second run restores nothing more'
);

select * from finish();
rollback;
