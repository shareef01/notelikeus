-- R20.3: what a NULL `p_base_revision` means to the server.
--
-- R20.2 binds the revision epoch to the logical operation, so an F-10-issued operation whose dataset was
-- replaced behind it can no longer read its former revision — `revisionState.read(R, …)` returns null and
-- the transport sends `p_base_revision = null`. That is only safe if the server treats a null base as
-- *create-only*, and refuses everything else. These lanes are that proof, executed against the real
-- functions rather than against a Kotlin double.
--
-- The predicates, from the migrations themselves:
--
--   apply_note_change:  IF FOUND THEN
--                         IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision
--                           -> conflict + current row
--                       …
--                       IF p_base_revision IS NOT NULL -> conflict 'note_not_found'   (absent + a base)
--                       …
--                       INSERT                                          (absent + no base)
--   apply_note_delete:  the same first predicate; absent + a base -> conflict 'note_not_found'
--
-- So a null base never updates and never deletes: with no base, an existing note is a conflict.

begin;

select plan(10);

create temporary table r203_ids as
select (select tests.create_supabase_user('r203_owner')) as owner_id;
grant select on r203_ids to authenticated;

select tests.authenticate_as('r203_owner');

-- The replacement dataset's note 42, already committed by whoever holds the account now.
select public.apply_note_change(
    '42', 42, null, 'replacement', 'replacement body', 9000, 0, false, false, false, 0, null,
    '[]'::jsonb, '[]'::jsonb
);
select public.apply_note_change(
    '42', 42, (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    'replacement', 'replacement body', 9100, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb
);

-- OCC-NULL-1: a late issued operation's write, with no base revision, against an existing note.
select is(
    (public.apply_note_change(
        '42', 42, null, 'stale writer', 'stale body', 9200, 0, false, false, false, 0, null,
        '[]'::jsonb, '[]'::jsonb
    ) ->> 'status'),
    'conflict',
    'OCC-NULL-1 a null base revision on an existing note is refused, not applied'
);
select isnt(
    (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    null::bigint,
    'OCC-NULL-1 the existing note is still there'
);

-- OCC-NULL-2: the same for a delete.
select is(
    (public.apply_note_delete('42', null) ->> 'status'),
    'conflict',
    'OCC-NULL-2 a null base revision cannot delete an existing note'
);
select is(
    (select count(*)::int from public.notes where note_id = '42' and owner_id = auth.uid()),
    1,
    'OCC-NULL-2 the replacement note survived the unbased delete'
);

-- OCC-NULL-3: with no base revision and no note, creation is the legitimate outcome.
select is(
    (public.apply_note_change(
        '77', 77, null, 'new note', 'new body', 9300, 0, false, false, false, 0, null,
        '[]'::jsonb, '[]'::jsonb
    ) ->> 'status'),
    'applied',
    'OCC-NULL-3 a null base revision still creates a note that does not exist'
);

-- OCC-NULL-4/§4: the F-10 composition. A replacement cloud note at revision 200, and an operation issued
-- before the dataset was replaced whose revision context was discarded — so its payload carries no base
-- revision. It must not overwrite the replacement's state.
create temporary table r203_replacement as
select revision as before_revision, title as before_title, content as before_content
from public.notes where note_id = '42' and owner_id = auth.uid();
grant select on r203_replacement to authenticated;

select is(
    (public.apply_note_change(
        '42', 42, null, 'late N writer', 'late N body', 9400, 0, false, false, false, 0, null,
        '[]'::jsonb, '[]'::jsonb
    ) ->> 'status'),
    'conflict',
    'OCC-NULL-4 a late issued operation cannot apply over the replacement note'
);
select is(
    (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    (select before_revision from r203_replacement),
    'OCC-NULL-4 the replacement revision is untouched'
);
select is(
    (select title || '|' || content from public.notes where note_id = '42' and owner_id = auth.uid()),
    (select before_title || '|' || before_content from r203_replacement),
    'OCC-NULL-4 the replacement content is untouched'
);
select is(
    (public.apply_note_delete('42', null) ->> 'status'),
    'conflict',
    'OCC-NULL-4 and the late unbased delete cannot remove it either'
);

-- A wrong-but-present revision is refused in the same way, so the null case is not a special path.
select is(
    (public.apply_note_delete('42', 1) ->> 'status'),
    'conflict',
    'OCC-NULL-5 a stale numeric base revision is refused exactly like a null one'
);

select * from finish();
rollback;
