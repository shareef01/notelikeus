-- R19.2: attachment metadata is provisional until the note that references it commits.
--
-- An upload's metadata row is created before the note that references the attachment is pushed. If that
-- note continuation goes stale -- a dataset transition landed behind the upload, which F-10 allows -- the
-- row used to keep exactly the shape a committed row has, and a replacement dataset that reuses the same
-- note id (the normal case after a same-UID isolation or a reinstall) then hydrated the object onto an
-- unrelated note. `committed_at` records whether a *successful note commit* named the row, and only
-- committed rows hydrate.
--
-- These lanes cover the backend half of R19 directly; the Worker's route and commitment behaviour lives
-- in `workers/attachments/src/index.test.ts` (WATT-1..7) and the client's protocol in
-- `AttachmentDeferredUploadProtocolTest`.

begin;

select plan(17);

-- The two accounts, and their uuids for the object keys the real Worker derives from the authenticated
-- account. (The helpers key users by their `test_identifier`, so the uuids are read back by hand.)
create temporary table r191_ids as
select
    (select tests.create_supabase_user('r191_owner')) as owner_id,
    (select tests.create_supabase_user('r191_other')) as other_id;

-- The lanes read their uuids while authenticated as those users, so the fixture has to be readable by
-- the role `authenticate_as` switches to.
grant select on r191_ids to authenticated;

select tests.authenticate_as('r191_owner');

-- The owner's note 42: a replacement dataset's note, reusing an id a previous dataset also used.
select public.apply_note_change(
    '42', 42, null, 'replacement', 'body', 9000, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb
);

-- R2ORPH-1/§17: a deferred-protocol upload whose note continuation never commits. The row is created
-- provisional, exactly as the Worker's v2 route does it.
select public.finalize_note_attachment_put(
    '42', 'att-old', 'owners/' || (select owner_id from r191_ids) || '/notes/42/att-old',
    'image/png', 3, true, 'image'
);

select is(
    public.list_user_attachments(),
    '[]'::jsonb,
    'R2ORPH-1 an uncommitted upload is invisible to hydration'
);
select is(
    (select committed_at from public.note_attachments where attachment_id = 'att-old'),
    null::timestamptz,
    'R2ORPH-1 the uncommitted row is provisional on the server'
);

-- A second, unrelated provisional row for the *same* note, created by a later continuation.
select public.finalize_note_attachment_put(
    '42', 'att-sibling', 'owners/' || (select owner_id from r191_ids) || '/notes/42/att-sibling',
    'image/png', 3, true, 'image'
);

-- §18: the note commits, naming only att-new — a legitimate current upload.
select public.finalize_note_attachment_put(
    '42', 'att-new', 'owners/' || (select owner_id from r191_ids) || '/notes/42/att-new',
    'image/png', 3, true, 'image'
);
select public.apply_note_change(
    '42', 42, (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    'replacement', 'body', 9100, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb, '["att-new"]'::jsonb
);

select is(
    (select jsonb_agg(x ->> 'attachment_id') from jsonb_array_elements(public.list_user_attachments()) x),
    '["att-new"]'::jsonb,
    'R2ORPH-1/§16 hydration sees exactly the committed reference'
);
select isnt(
    (select committed_at from public.note_attachments where attachment_id = 'att-new'),
    null::timestamptz,
    '§18 the named attachment became visible in the same transaction as the note'
);
select is(
    (select committed_at from public.note_attachments where attachment_id = 'att-old'),
    null::timestamptz,
    '§16 a provisional row the commit did not name stays provisional'
);
select is(
    (select committed_at from public.note_attachments where attachment_id = 'att-sibling'),
    null::timestamptz,
    'R2ORPH-5 an unrelated same-note provisional row is not promoted'
);

-- §24/§25: a fresh session for the same account. The fact is server-owned, so a second device — or a
-- process that restarted — sees exactly the same thing, with no shared local state.
select tests.authenticate_as('r191_owner');
select is(
    (select jsonb_agg(x ->> 'attachment_id') from jsonb_array_elements(public.list_user_attachments()) x),
    '["att-new"]'::jsonb,
    'R2ORPH-2/§24 a second session still hides the orphan and still sees the committed row'
);

-- §19: notes commit one RPC at a time, and each commit names only its own attachments.
select public.apply_note_change(
    '43', 43, null, 'second note', 'body', 9100, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb
);
select public.finalize_note_attachment_put(
    '43', 'att-other', 'owners/' || (select owner_id from r191_ids) || '/notes/43/att-other',
    'image/png', 3, true, 'image'
);
select public.apply_note_change(
    '43', 43, (select revision from public.notes where note_id = '43' and owner_id = auth.uid()),
    'second note', 'body', 9200, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb, '["att-other"]'::jsonb
);
select is(
    (select committed_at from public.note_attachments where attachment_id = 'att-sibling'),
    null::timestamptz,
    '§19 another note''s commit did not promote note 42''s provisional row'
);
select isnt(
    (select committed_at from public.note_attachments where attachment_id = 'att-other'),
    null::timestamptz,
    '§19 the second note''s own attachment was promoted'
);

-- §20/R2ORPH-7: an older client's upload — the six-argument arity — commits at upload, as it always did.
select public.finalize_note_attachment_put(
    '42', 'att-legacy', 'owners/' || (select owner_id from r191_ids) || '/notes/42/att-legacy',
    'image/png', 3, 'image'
);
select isnt(
    (select committed_at from public.note_attachments where attachment_id = 'att-legacy'),
    null::timestamptz,
    'R2ORPH-7 an upload that does not opt into the protocol stays visible, exactly as before'
);

-- R2ORPH-6/§9 authorization: another account cannot write into this owner's namespace.
select tests.authenticate_as('r191_other');
select throws_ok(
    $$ select public.finalize_note_attachment_put(
           '42', 'att-old',
           'owners/' || (select other_id from r191_ids) || '/notes/42/att-old', 'image/png', 3, true, 'image') $$,
    null,
    null,
    'R2ORPH-6 another account cannot create a row in this owner''s namespace'
);
select tests.authenticate_as('r191_owner');
select is(
    (select committed_at from public.note_attachments where attachment_id = 'att-old'),
    null::timestamptz,
    'R2ORPH-6 the orphan is still provisional after the other account''s attempt'
);

-- §15/§33: naming one id promotes exactly that row. The id named here is a provisional row of the same
-- note and the same account, so the promotion stays scoped to what the commit named.
select public.apply_note_change(
    '42', 42, (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    'replacement', 'body', 9300, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb, '["att-old"]'::jsonb
);
select isnt(
    (select committed_at from public.note_attachments where attachment_id = 'att-old'),
    null::timestamptz,
    '§15 naming a row promotes exactly that row'
);

-- §15: an id the commit names that has no row at all (an imported reference, or a malformed local
-- attachment) is a no-op for the promotion — the note commit still succeeds and nothing else moves.
select set_config(
    'r191.committed_before',
    (select count(*)::text from public.note_attachments where committed_at is not null and note_id = '42'),
    true
);
select public.apply_note_change(
    '42', 42, (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    'replacement', 'body', 9400, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb,
    '["att-does-not-exist"]'::jsonb
);
select is(
    (select count(*)::text from public.note_attachments where committed_at is not null and note_id = '42'),
    current_setting('r191.committed_before'),
    '§15 naming an unknown id promotes nothing, and does not disturb the rows already committed'
);

-- R2LEGACY-1: the residual the `/v1` route still carries, pinned as current behaviour rather than
-- asserted away. A legacy client's upload commits its row at upload time, and the note commit it belongs
-- to names nothing -- the 14-argument arity predates attachment ids -- so there is no fact on either
-- request that could bind the two operations. `att-legacy-orphan` is uploaded for note 42, no commit
-- ever names it, and a replacement note 42 commits naming nothing: the row is visible throughout.
--
-- This lane is the accepted compatibility debt, not a property to be "fixed" by weakening the promotion
-- rule: promoting every provisional row when note 42 commits would adopt exactly this upload into a
-- replacement note, which is the collision R19 exists to prevent.
select public.finalize_note_attachment_put(
    '42', 'att-legacy-orphan', 'owners/' || (select owner_id from r191_ids) || '/notes/42/att-legacy-orphan',
    'image/png', 3, 'image'
);
select ok(
    public.list_user_attachments() @> '[{"attachment_id": "att-legacy-orphan"}]'::jsonb,
    'R2LEGACY-1 a legacy v1 upload is immediately visible, before any note commit'
);
select public.apply_note_change(
    '42', 42, (select revision from public.notes where note_id = '42' and owner_id = auth.uid()),
    'replacement', 'body', 9500, 0, false, false, false, 0, null, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb
);
select ok(
    public.list_user_attachments() @> '[{"attachment_id": "att-legacy-orphan"}]'::jsonb,
    'R2LEGACY-1 (accepted debt) a replacement commit leaves the legacy upload visible'
);

-- R2LEGACY-3/§14: the legacy residual does not disturb the rows that were already committed. Historical
-- attachments remain visible, and the deferred protocol did not reinterpret any of them as provisional.
select ok(
    public.list_user_attachments() @> '[{"attachment_id": "att-legacy"}]'::jsonb,
    'R2LEGACY-3 a historically committed attachment is still visible'
);

select * from finish();
rollback;
