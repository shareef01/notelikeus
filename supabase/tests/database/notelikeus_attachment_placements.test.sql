begin;

select plan(14);

create temporary table placement_fixtures as
select tests.create_supabase_user('placements_owner') as owner_id,
       tests.create_supabase_user('placements_other') as other_id,
       E'Before 🧭\n\nAfter' as body;
grant select on placement_fixtures to authenticated;

create function tests.commit_placements(p_revision bigint, p_images jsonb, p_placements jsonb)
returns jsonb language sql security invoker as $$
    select public.apply_note_change_with_placements(
        '42', 42, p_revision, 'Image note', (select body from placement_fixtures),
        9000, 0, false, false, false, 0, null, '[]', '[]', p_images, 0, p_placements
    );
$$;

create function tests.placement_payload(p_id text, p_offset integer)
returns jsonb language sql stable security invoker as $$
    select jsonb_build_array(jsonb_build_object(
        'attachmentId', p_id,
        'bodySha256', encode(sha256(convert_to((select body from placement_fixtures), 'UTF8')), 'hex'),
        'offset', p_offset, 'ordinal', 0
    ));
$$;

select tests.authenticate_as('placements_owner');
select is(public.get_sync_generation()->>'attachment_placements', 'true', 'the server advertises placement support');

select public.apply_note_change(
    '42', 42, null, 'Image note', 'Draft', 8999, 0, false, false, false, 0, null, '[]', '[]', '[]', 0
);
select public.apply_note_change(
    '43', 43, null, 'Other note', 'Other', 8999, 0, false, false, false, 0, null, '[]', '[]', '[]', 0
);

select public.finalize_note_attachment_put(
    '42', 'att-in-note', 'owners/' || (select owner_id from placement_fixtures) || '/notes/42/att-in-note',
    'image/png', 3, true, 'image'
);
select public.finalize_note_attachment_put(
    '42', 'att-provisional', 'owners/' || (select owner_id from placement_fixtures) || '/notes/42/att-provisional',
    'image/png', 3, true, 'image'
);
select public.finalize_note_attachment_put(
    '43', 'att-other-note', 'owners/' || (select owner_id from placement_fixtures) || '/notes/43/att-other-note',
    'image/png', 3, true, 'image'
);

select is(tests.commit_placements((select revision from public.notes where note_id = '42'), '["att-in-note"]', tests.placement_payload('att-in-note', 10))->>'status',
    'applied', 'the note, attachment promotion, and Unicode scalar position commit together');
select is(public.list_user_attachments()->0->'placement',
    (tests.placement_payload('att-in-note', 10)->0) - 'attachmentId', 'hydration returns the committed position');
select is(jsonb_array_length(public.list_user_attachments()), 1, 'unreferenced provisional images remain invisible');

select is(tests.commit_placements(null, '["att-in-note"]', '[]')->>'status', 'conflict',
    'a stale revision cannot clear the position');
select is(public.list_user_attachments()->0->'placement'->>'offset', '10', 'the conflict leaves the position in place');

create temporary table placement_revision as select revision from public.notes where note_id = '42';
select throws_ok($$select tests.commit_placements(
    (select revision from placement_revision), '["att-in-note"]', tests.placement_payload('att-in-note', 7)
)$$, '22023', 'image placement is not at a body boundary', 'an offset inside a paragraph is refused');
select is((select revision from public.notes where note_id = '42'), (select revision from placement_revision),
    'invalid placement rolls back the accompanying note revision');
select throws_ok($$select tests.commit_placements(
    (select revision from placement_revision), '["att-other-note"]', tests.placement_payload('att-other-note', 10)
)$$, '22023', 'image placement does not name a distinct committed image on this note',
    'a position cannot name an upload from another note');
select throws_ok($$select tests.commit_placements(
    (select revision from placement_revision), '["att-in-note"]', tests.placement_payload('att-provisional', 10)
)$$, '22023', 'image placement does not name a distinct committed image on this note',
    'a position cannot name a provisional upload this commit did not promote');

select public.apply_note_change(
    '42', 42, (select revision from public.notes where note_id = '42'), 'Retitled',
    (select body from placement_fixtures), 9001, 0, false, false, false, 0, null, '[]', '[]', '["att-in-note"]', 0
);
select is(public.list_user_attachments()->0->'placement'->>'offset', '10', 'a legacy note edit preserves the position');

select tests.authenticate_as('placements_other');
select is(public.list_user_attachments(), '[]'::jsonb, 'another account cannot see the image or its position');
select tests.authenticate_as('placements_owner');

select is(tests.commit_placements((select revision from public.notes where note_id = '42'), '["att-in-note"]', '[]')->>'status',
    'applied', 'an explicit empty payload clears all positions on the note');
select ok(NOT (public.list_user_attachments()->0 ? 'placement'), 'the image remains visible without a position');

select * from finish();
rollback;
