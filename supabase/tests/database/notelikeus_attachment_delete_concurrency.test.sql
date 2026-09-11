begin;
select plan(12);

-- Ordered interleavings that recreate the F46/F52 race windows without a second connection.
--
-- True concurrent sessions need dblink (and committed fixtures); pgTAP files run inside
-- begin/rollback so a second session cannot see this work. Deterministic orderings still
-- exercise the windows that mattered: claim visible before restore, and claim visible
-- before finalize_put of the same identity.
--
-- Structural invariant after every step (CHECK note_attachments_claimed_delete_is_terminal):
--   no live row may carry delete_claimed_at or purge_claimed_at.

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

create function tests.assert_no_live_claimed_attachment()
returns bigint
language sql
stable
as $$
  select count(*)::bigint
  from public.note_attachments
  where deleted_at is null
    and (delete_claimed_at is not null or purge_claimed_at is not null);
$$;

select tests.create_supabase_user('race_a@notelikeus.test');
select tests.authenticate_as('race_a@notelikeus.test');

-- ---------------------------------------------------------------------------
-- Interleave 1: owner claim, then restore_note.
-- Claimed identity must stay dead; cascade-only deletes may revive; CHECK holds.
-- ---------------------------------------------------------------------------
select public.apply_note_change(
  '701', 701::bigint, null::bigint,
  'Race restore', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '701', 'attRace1',
  public.expected_attachment_object_key(auth.uid(), '701', 'attRace1'),
  'image/png', 100
);
select public.finalize_note_attachment_put(
  '701', 'attRace1b',
  public.expected_attachment_object_key(auth.uid(), '701', 'attRace1b'),
  'image/png', 100
);

-- Claim only one attachment, then delete the whole note (cascades the other).
select public.begin_note_attachment_delete('701', 'attRace1');
select public.apply_note_delete('701', (select revision from public.notes where note_id = '701'));

select results_eq(
  $$ select tests.assert_no_live_claimed_attachment() $$,
  ARRAY[0::bigint],
  'after claim + note delete: CHECK holds (no live claimed row)'
);

select public.restore_note(
  '701', 701::bigint, null::bigint,
  'Race restore', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

select results_eq(
  $$ select count(*)::bigint from public.note_attachments
     where attachment_id = 'attRace1' and deleted_at is null $$,
  ARRAY[0::bigint],
  'restore_note does not revive an owner-claimed attachment'
);
select results_eq(
  $$ select count(*)::bigint from public.note_attachments
     where attachment_id = 'attRace1b' and deleted_at is null $$,
  ARRAY[1::bigint],
  'restore_note does revive the cascade-only sibling attachment'
);
select results_eq(
  $$ select tests.assert_no_live_claimed_attachment() $$,
  ARRAY[0::bigint],
  'after restore: CHECK still holds'
);

-- ---------------------------------------------------------------------------
-- Interleave 2: owner claim, then finalize_put of the SAME identity.
-- ---------------------------------------------------------------------------
select public.apply_note_change(
  '702', 702::bigint, null::bigint,
  'Race put', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '702', 'attRace2',
  public.expected_attachment_object_key(auth.uid(), '702', 'attRace2'),
  'image/png', 100
);
select public.begin_note_attachment_delete('702', 'attRace2');

select results_eq(
  $$ select public.finalize_note_attachment_put(
       '702', 'attRace2',
       public.expected_attachment_object_key(auth.uid(), '702', 'attRace2'),
       'image/png', 50)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'finalize_put after claim refuses the same identity (F52 window)'
);
select results_eq(
  $$ select count(*)::bigint from public.note_attachments
     where attachment_id = 'attRace2' and deleted_at is null $$,
  ARRAY[0::bigint],
  'refused finalize left the claimed row deleted'
);
select results_eq(
  $$ select tests.assert_no_live_claimed_attachment() $$,
  ARRAY[0::bigint],
  'after refused finalize: CHECK holds'
);

-- Replacement content uses a NEW id (documented architecture).
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '702', 'attRace2new',
       public.expected_attachment_object_key(auth.uid(), '702', 'attRace2new'),
       'image/png', 50)->>'attachment_id' $$,
  ARRAY['attRace2new'::text],
  'a fresh attachment id still finalizes after the old one was claimed'
);

-- ---------------------------------------------------------------------------
-- Interleave 3: claim, confirm object deleted, then restore_note + re-PUT.
-- ---------------------------------------------------------------------------
select public.apply_note_change(
  '703', 703::bigint, null::bigint,
  'Race confirm', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '703', 'attRace3',
  public.expected_attachment_object_key(auth.uid(), '703', 'attRace3'),
  'image/png', 100
);
select public.begin_note_attachment_delete('703', 'attRace3');
select public.finalize_note_attachment_delete('703', 'attRace3');
select public.apply_note_delete('703', (select revision from public.notes where note_id = '703'));
select public.restore_note(
  '703', 703::bigint, null::bigint,
  'Race confirm', 'body', 101::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);

select results_eq(
  $$ select count(*)::bigint from public.note_attachments
     where attachment_id = 'attRace3' and deleted_at is null $$,
  ARRAY[0::bigint],
  'confirmed deletion stays dead across restore_note'
);
select results_eq(
  $$ select public.finalize_note_attachment_put(
       '703', 'attRace3',
       public.expected_attachment_object_key(auth.uid(), '703', 'attRace3'),
       'image/png', 10)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'confirmed deletion cannot be finalized live again after restore'
);
select results_eq(
  $$ select tests.assert_no_live_claimed_attachment() $$,
  ARRAY[0::bigint],
  'after confirm + restore + refused put: CHECK holds'
);

-- Early gates agree with finalize on a claimed identity (same race window, earlier cut).
select public.apply_note_change(
  '704', 704::bigint, null::bigint,
  'Race precheck', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
  '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
  '704', 'attRace4',
  public.expected_attachment_object_key(auth.uid(), '704', 'attRace4'),
  'image/png', 100
);
select public.begin_note_attachment_delete('704', 'attRace4');

select results_eq(
  $$ select public.precheck_note_attachment_put('704', 'attRace4', 'image/png', null)->>'reason' $$,
  ARRAY['terminally_deleted'::text],
  'precheck refuses the claimed identity before any Worker body read'
);

select * from finish();
rollback;
