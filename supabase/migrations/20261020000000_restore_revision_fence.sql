-- F-14: fence `restore_note` on the revision the caller believed.
--
-- `apply_note_change` and `apply_note_delete` both compare `p_base_revision` with the stored
-- revision and answer `conflict` when they disagree. `restore_note` took the same parameter and
-- never read it — it deleted the tombstone and then unconditionally updated or inserted — and it
-- had exactly one return path, `applied`. `supabase db lint` is what surfaced the unused
-- parameter; the client conflict branches that were written for it were unreachable.
--
-- The consequence was not theoretical. A device that had been offline while another device edited
-- the note could undo a delete and put its older copy on top of the newer one, with no conflict
-- and nothing reported. Every other mutation is fenced against exactly that; restore was the one
-- that writes content without being fenced.
--
-- The rule now, in the order the function reaches it:
--
--   * the note is live  -> the caller must present its current revision. `NULL` is refused too:
--     "I do not know the baseline" is not a licence to overwrite what is there, which is the same
--     answer `apply_note_change` gives for a null base against an existing row. A refusal carries
--     `current` so the caller can learn the revision.
--   * there is no live row -> the restore applies, whatever baseline was claimed. Nothing is being
--     overwritten, so there is nothing for a fence to protect, and this is the ordinary undo path:
--     the tombstone is the record of the delete the user is undoing. Refusing here as well was
--     tried and reverted — it refused a legitimate undo purely for presenting a baseline.
--
-- What the fence deliberately does not do: stop a restore from consuming a tombstone another
-- device wrote. That is a cross-device conflict of its own, pre-existing, and separate from a
-- stale copy overwriting a live row. It is recorded as such in the audit report rather than
-- silently folded into this change.
--
-- The wipe generation is unaffected and unrelated: it fences a wiped account, this fences a stale
-- edit on a live one. Both can refuse the same call, the wipe guard first because it is a trigger
-- on the write and this is a check inside the RPC.
--
-- Compatibility: the arity, the parameter list and the grants are unchanged. A client that
-- presents the revision it knows (Kotlin does; web now does) sees no difference on the path it
-- actually takes — an undo of a delete, where the row is absent. A client that could not present
-- one now gets `conflict` instead of a silent overwrite, which is the change.

CREATE OR REPLACE FUNCTION public.restore_note(
    p_note_id TEXT,
    p_local_id BIGINT,
    p_base_revision BIGINT,
    p_title TEXT,
    p_content TEXT,
    p_client_timestamp BIGINT,
    p_color INTEGER,
    p_is_pinned BOOLEAN,
    p_is_archived BOOLEAN,
    p_is_trashed BOOLEAN,
    p_position INTEGER,
    p_reminder_timestamp BIGINT,
    p_labels JSONB,
    p_checklist JSONB
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_existing public.notes%ROWTYPE;
    v_note_is_live BOOLEAN := false;
    v_new_revision BIGINT;
    v_server_updated_at TIMESTAMPTZ := timezone('utc', now());
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    IF p_note_id IS DISTINCT FROM p_local_id::text THEN
        RAISE EXCEPTION 'note_id must match local_id text form' USING ERRCODE = '22023';
    END IF;

    PERFORM public.validate_note_payload(
        p_title, p_content, p_color, p_position, p_client_timestamp,
        p_reminder_timestamp, COALESCE(p_labels, '[]'::jsonb), COALESCE(p_checklist, '[]'::jsonb)
    );

    -- Locks the tombstone, so two restores of the same note serialise here.
    PERFORM 1 FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    -- Read and fence *before* anything is written. `v_note_is_live` is captured explicitly because
    -- the DELETE below would otherwise reset FOUND and silently select the INSERT branch.
    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;
    v_note_is_live := FOUND;

    IF v_note_is_live THEN
        IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
            RETURN jsonb_build_object(
                'status', 'conflict',
                'error', 'note_changed',
                'current', public.note_row_to_json(v_existing)
            );
        END IF;
    END IF;

    DELETE FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = p_note_id;

    v_new_revision := nextval('public.sync_revision_seq');

    IF v_note_is_live THEN
        UPDATE public.notes
        SET
            revision = v_new_revision,
            title = p_title,
            content = p_content,
            client_timestamp = p_client_timestamp,
            color = p_color,
            is_pinned = p_is_pinned,
            is_archived = p_is_archived,
            is_trashed = p_is_trashed,
            position = p_position,
            reminder_timestamp = p_reminder_timestamp,
            labels = COALESCE(p_labels, '[]'::jsonb),
            checklist = COALESCE(p_checklist, '[]'::jsonb),
            server_updated_at = v_server_updated_at
        WHERE owner_id = v_owner AND note_id = p_note_id;
    ELSE
        INSERT INTO public.notes (
            note_id, local_id, owner_id, revision, title, content, client_timestamp,
            color, is_pinned, is_archived, is_trashed, position, reminder_timestamp,
            labels, checklist, server_updated_at
        ) VALUES (
            p_note_id, p_local_id, v_owner, v_new_revision, p_title, p_content, p_client_timestamp,
            p_color, p_is_pinned, p_is_archived, p_is_trashed, p_position, p_reminder_timestamp,
            COALESCE(p_labels, '[]'::jsonb), COALESCE(p_checklist, '[]'::jsonb), v_server_updated_at
        );
    END IF;

    -- Either claim means this attachment's bytes are gone or going: purge_claimed_at is the
    -- sweeper's, delete_claimed_at is the owner's own delete. Bringing such a row back to live
    -- would leave metadata pointing at an object that no longer exists, so a claimed attachment
    -- stays deleted even though its note is restored. Attachments deleted only as a side effect
    -- of deleting the note carry neither stamp, and still come back.
    UPDATE public.note_attachments
    SET deleted_at = NULL,
        purge_requested_at = NULL
    WHERE owner_id = v_owner
      AND note_id = p_note_id
      AND deleted_at IS NOT NULL
      AND purge_claimed_at IS NULL
      AND delete_claimed_at IS NULL;

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

REVOKE ALL ON FUNCTION public.restore_note(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.restore_note(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) TO authenticated;
