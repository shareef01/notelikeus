-- Two audit findings of 2026-11-02, both about a per-owner invariant nothing was enforcing.
--
-- 1. Revisions must become visible in revision order, per account.
--
--    `pull_changes` is a cursor over `revision`, and a revision comes from `sync_revision_seq` before
--    its transaction commits. Device A takes 100, device B takes 101, B commits first, a client pulls
--    and moves its cursor to 101; when A commits, revision 100 sits below every later pull. The edit or
--    delete stays hidden from that client until its next full snapshot, which only a new sync session
--    takes. The revision fence still stops an overwrite, so this hides data rather than losing it.
--
--    Each revision-writing RPC now takes a per-owner lock, held until commit, before it allocates one.
--    An account's next revision therefore cannot be allocated until the previous one has committed.
--    Other accounts are unaffected: a cursor only ever covers its own owner's rows. The lock comes
--    before any row lock in all three RPCs, so they cannot deadlock against each other, and the
--    generation-presenting wrappers need no change because they delegate to these.
--
-- 2. Attachment quota must cover the bytes actually stored.
--
--    The 200 MB quota summed `deleted_at IS NULL`, but deleting a note sets `deleted_at` immediately
--    while its objects stay in R2 until a client or the sweep removes them. Upload, delete the note,
--    repeat: storage was bounded by the rate limit, not the quota.
--
--    Quota now counts a soft-deleted row until `object_deleted_at` confirms its object is gone, or the
--    row is purged. It counts only rows something will reclaim (claimed, marked by a wipe, or belonging
--    to a tombstoned note). A row soft-deleted before claims existed, on a note that is still live, is
--    reclaimed by nothing, so counting it would take that quota away for good.
--
--    The per-note limit of 20 keeps using `deleted_at`: it limits what a note shows, not what is stored.
--
-- Bodies are generated from each function's latest definition; only the lines above differ. Signatures
-- and grants are unchanged, so no client or Worker change is required and the rollout order is free.
--
-- Rollback: restore the six bodies from 20261019000000 (apply_note_change, apply_note_delete,
-- finalize_note_attachment_put), 20261020000000 (restore_note) and 20260908130000 (precheck and
-- authorize), then drop the helper. No data changes either way.

/*
 * Bytes this owner holds in storage, leaving out one attachment id (the one being re-uploaded).
 *
 * Runs as the invoker, so row-level security already confines it to the caller's own rows, and the
 * service role never calls it. In `notelikeus_internal` so it is not addressable as an RPC.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.attachment_stored_bytes(
    p_owner UUID,
    p_excluding_attachment_id TEXT
)
RETURNS BIGINT
LANGUAGE sql
STABLE
SET search_path = public
AS $$
    SELECT coalesce(sum(a.size_bytes), 0)::bigint
    FROM public.note_attachments a
    WHERE a.owner_id = p_owner
      AND a.attachment_id <> p_excluding_attachment_id
      AND a.object_deleted_at IS NULL
      AND (
          a.deleted_at IS NULL
          OR a.delete_claimed_at IS NOT NULL
          OR a.purge_claimed_at IS NOT NULL
          OR a.purge_requested_at IS NOT NULL
          OR EXISTS (
              SELECT 1 FROM public.note_tombstones t
              WHERE t.owner_id = a.owner_id AND t.note_id = a.note_id
          )
      );
$$;

REVOKE ALL ON FUNCTION notelikeus_internal.attachment_stored_bytes(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION notelikeus_internal.attachment_stored_bytes(UUID, TEXT) TO authenticated, service_role;

-- ---------------------------------------------------------------------------------------------
-- Revision writers: the per-owner lock, immediately after the caller is known.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.apply_note_change(
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
    p_checklist JSONB,
    p_attachment_ids JSONB
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $function$

DECLARE
    v_owner UUID := auth.uid();
    v_existing public.notes%ROWTYPE;
    v_new_revision BIGINT;
    v_server_updated_at TIMESTAMPTZ := timezone('utc', now());
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended('note_sync_write:' || v_owner::text, 0));

    IF p_note_id IS DISTINCT FROM p_local_id::text THEN
        RAISE EXCEPTION 'note_id must match local_id text form' USING ERRCODE = '22023';
    END IF;

    PERFORM public.validate_note_payload(
        p_title, p_content, p_color, p_position, p_client_timestamp,
        p_reminder_timestamp, COALESCE(p_labels, '[]'::jsonb), COALESCE(p_checklist, '[]'::jsonb)
    );

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    IF FOUND THEN
        IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
            PERFORM notelikeus_internal.end_sync_mutation();
            RETURN jsonb_build_object(
                'status', 'conflict',
                'current', public.note_row_to_json(v_existing)
            );
        END IF;

        v_new_revision := nextval('public.sync_revision_seq');
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
        WHERE owner_id = v_owner AND note_id = p_note_id
        RETURNING revision INTO v_new_revision;

        PERFORM notelikeus_internal.end_sync_mutation();
    -- R19.1: the note version above has committed, in this same transaction, and these are the
    -- attachments it names. Exactly these rows become visible: a provisional row for the same note that
    -- this version does *not* name -- an orphan left by an earlier continuation that went stale -- stays
    -- provisional, and therefore stays out of hydration.
    --
    -- The promotion is inline rather than a helper the client could call: `v_owner` is `auth.uid()`, so
    -- only the committing account's own rows are eligible, RLS restricts the update to them, and there is
    -- no separate entry point that could be pointed at another account's rows.
    IF p_attachment_ids IS NOT NULL THEN
        -- `sync_mutation_guard` gates direct table writes on this transaction-local flag; the note
        -- write above released it, so it is re-asserted for this, the same transaction's promotion.
        PERFORM set_config('notelikeus.sync_mutation', '1', true);
        UPDATE public.note_attachments a
        SET committed_at = coalesce(a.committed_at, v_server_updated_at)
        WHERE a.owner_id = v_owner
          AND a.note_id = p_note_id
          AND a.deleted_at IS NULL
          AND a.committed_at IS NULL
          AND a.attachment_id IN (
              SELECT trim(value) FROM jsonb_array_elements_text(p_attachment_ids) AS value
          );
    END IF;

        RETURN jsonb_build_object(
            'status', 'applied',
            'revision', v_new_revision,
            'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
        );
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = p_note_id
    ) THEN
        PERFORM notelikeus_internal.end_sync_mutation();
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_deleted');
    END IF;

    IF p_base_revision IS NOT NULL THEN
        PERFORM notelikeus_internal.end_sync_mutation();
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_not_found');
    END IF;

    v_new_revision := nextval('public.sync_revision_seq');
    INSERT INTO public.notes (
        note_id,
        local_id,
        owner_id,
        revision,
        title,
        content,
        client_timestamp,
        color,
        is_pinned,
        is_archived,
        is_trashed,
        position,
        reminder_timestamp,
        labels,
        checklist,
        server_updated_at
    ) VALUES (
        p_note_id,
        p_local_id,
        v_owner,
        v_new_revision,
        p_title,
        p_content,
        p_client_timestamp,
        p_color,
        p_is_pinned,
        p_is_archived,
        p_is_trashed,
        p_position,
        p_reminder_timestamp,
        COALESCE(p_labels, '[]'::jsonb),
        COALESCE(p_checklist, '[]'::jsonb),
        v_server_updated_at
    );

    PERFORM notelikeus_internal.end_sync_mutation();
    -- R19.1: the note version above has committed, in this same transaction, and these are the
    -- attachments it names. Exactly these rows become visible: a provisional row for the same note that
    -- this version does *not* name -- an orphan left by an earlier continuation that went stale -- stays
    -- provisional, and therefore stays out of hydration.
    --
    -- The promotion is inline rather than a helper the client could call: `v_owner` is `auth.uid()`, so
    -- only the committing account's own rows are eligible, RLS restricts the update to them, and there is
    -- no separate entry point that could be pointed at another account's rows.
    IF p_attachment_ids IS NOT NULL THEN
        -- `sync_mutation_guard` gates direct table writes on this transaction-local flag; the note
        -- write above released it, so it is re-asserted for this, the same transaction's promotion.
        PERFORM set_config('notelikeus.sync_mutation', '1', true);
        UPDATE public.note_attachments a
        SET committed_at = coalesce(a.committed_at, v_server_updated_at)
        WHERE a.owner_id = v_owner
          AND a.note_id = p_note_id
          AND a.deleted_at IS NULL
          AND a.committed_at IS NULL
          AND a.attachment_id IN (
              SELECT trim(value) FROM jsonb_array_elements_text(p_attachment_ids) AS value
          );
    END IF;

    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$function$;

CREATE OR REPLACE FUNCTION public.apply_note_delete(
    p_note_id TEXT,
    p_base_revision BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_existing public.notes%ROWTYPE;
    v_new_revision BIGINT;
    v_deleted_at TIMESTAMPTZ := timezone('utc', now());
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended('note_sync_write:' || v_owner::text, 0));

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    IF NOT FOUND THEN
        IF EXISTS (
            SELECT 1 FROM public.note_tombstones
            WHERE owner_id = v_owner AND note_id = p_note_id
        ) THEN
            UPDATE public.note_attachments
            SET deleted_at = COALESCE(deleted_at, v_deleted_at)
            WHERE owner_id = v_owner AND note_id = p_note_id AND deleted_at IS NULL;
            PERFORM notelikeus_internal.end_sync_mutation();
            RETURN jsonb_build_object('status', 'applied', 'idempotent', true);
        END IF;
        PERFORM notelikeus_internal.end_sync_mutation();
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_not_found');
    END IF;

    IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
        PERFORM notelikeus_internal.end_sync_mutation();
        RETURN jsonb_build_object(
            'status', 'conflict',
            'current', public.note_row_to_json(v_existing)
        );
    END IF;

    UPDATE public.note_attachments
    SET deleted_at = v_deleted_at
    WHERE owner_id = v_owner AND note_id = p_note_id AND deleted_at IS NULL;

    DELETE FROM public.notes WHERE owner_id = v_owner AND note_id = p_note_id;

    v_new_revision := nextval('public.sync_revision_seq');
    INSERT INTO public.note_tombstones (note_id, owner_id, revision, deleted_at)
    VALUES (p_note_id, v_owner, v_new_revision, v_deleted_at)
    ON CONFLICT (owner_id, note_id) DO UPDATE
        SET revision = EXCLUDED.revision,
            deleted_at = EXCLUDED.deleted_at;

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'deleted_at', (extract(epoch FROM v_deleted_at) * 1000)::bigint
    );
END;
$$;

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

    PERFORM pg_advisory_xact_lock(hashtextextended('note_sync_write:' || v_owner::text, 0));

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

-- ---------------------------------------------------------------------------------------------
-- Quota readers: the stored-bytes sum in place of the live-rows sum.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.finalize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_object_key TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT,
    p_provisional BOOLEAN,
    p_attachment_type TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $function$

DECLARE
    v_owner UUID := auth.uid();
    v_key TEXT := trim(p_object_key);
    v_expected TEXT;
    v_attachment_id TEXT := trim(p_attachment_id);
    v_note_id TEXT := trim(p_note_id);
    v_mime TEXT := lower(split_part(trim(p_mime_type), ';', 1));
    v_type TEXT := coalesce(nullif(trim(p_attachment_type), ''), 'image');
    v_live_count INTEGER;
    v_user_bytes BIGINT;
    v_existing public.note_attachments%ROWTYPE;
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();


    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    -- Serialise quota accounting per owner. The count and the sum below are read before the
    -- insert, so two concurrent finalizations both saw the same pre-insert totals, both decided
    -- they fit, and together exceeded the limit. The lock is per owner and released at commit,
    -- so contention is bounded to one user's own concurrent uploads.
    PERFORM pg_advisory_xact_lock(hashtextextended('note_attachment_quota:' || v_owner::text, 0));
    IF NOT public.is_valid_attachment_path_id(v_attachment_id)
       OR NOT public.is_valid_attachment_path_id(v_note_id) THEN
        RAISE EXCEPTION 'invalid attachment or note id' USING ERRCODE = '22023';
    END IF;
    IF v_key = '' THEN
        RAISE EXCEPTION 'attachment_id, note_id, and object_key required' USING ERRCODE = '22023';
    END IF;
    IF v_type <> 'image' THEN
        RAISE EXCEPTION 'attachment_type not allowed' USING ERRCODE = '22023';
    END IF;
    IF NOT public.is_allowed_attachment_mime(v_mime) THEN
        RAISE EXCEPTION 'mime type not allowed' USING ERRCODE = '22023';
    END IF;
    IF p_size_bytes IS NULL OR p_size_bytes < 1 OR p_size_bytes > 10485760 THEN
        RAISE EXCEPTION 'size_bytes must be between 1 and 10485760' USING ERRCODE = '22023';
    END IF;

    PERFORM public.assert_live_owned_note(v_note_id);

    v_expected := public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id);
    IF v_key <> v_expected THEN
        RAISE EXCEPTION 'object_key does not match owner namespace' USING ERRCODE = '22023';
    END IF;

    -- The authoritative terminal-delete gate. It has to be here, holding the conflict row's own
    -- lock, because a DELETE can claim the attachment at any point after the Worker's preflight
    -- said yes -- including while its bytes are being uploaded. Locking the row this INSERT would
    -- conflict on serialises the two: either the claim is already visible here and this returns
    -- terminally_deleted, or this insert commits first and the claim waits and then wins.
    --
    -- Refused as a value rather than an exception on purpose. The Worker has usually written
    -- bytes by now, and it can only safely delete them again if the database says, without
    -- ambiguity, that this identity can never be live -- which an exception, a timeout and an
    -- unreachable database are indistinguishable from.
    SELECT * INTO v_existing
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND attachment_id = v_attachment_id
    FOR UPDATE;

    IF FOUND AND public.attachment_delete_is_terminal(v_existing) THEN
        -- This is the only non-exception exit after begin_sync_mutation, so it has to disarm the
        -- guard itself. Every other refusal here RAISEs and takes the transaction with it.
        PERFORM notelikeus_internal.end_sync_mutation();
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'terminally_deleted',
            'object_key', v_expected
        );
    END IF;

    SELECT count(*) INTO v_live_count
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_live_count >= 20 THEN
        RAISE EXCEPTION 'too many attachments on note (max 20)' USING ERRCODE = '22023';
    END IF;

    v_user_bytes := notelikeus_internal.attachment_stored_bytes(v_owner, v_attachment_id);
    IF v_user_bytes + p_size_bytes > 209715200 THEN
        RAISE EXCEPTION 'user attachment quota exceeded' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.note_attachments (
        attachment_id,
        owner_id,
        note_id,
        object_key,
        mime_type,
        size_bytes,
        attachment_type,
        deleted_at,
        committed_at
    )
    VALUES (
        v_attachment_id,
        v_owner,
        v_note_id,
        v_key,
        v_mime,
        p_size_bytes,
        v_type,
        NULL,
        -- A legacy upload commits the moment it is stored; a new-protocol upload stays provisional
        -- until the note that references it commits.
        CASE WHEN p_provisional THEN NULL ELSE timezone('utc', now()) END
    )
    ON CONFLICT (owner_id, attachment_id) DO UPDATE
        SET note_id = EXCLUDED.note_id,
            object_key = EXCLUDED.object_key,
            mime_type = EXCLUDED.mime_type,
            size_bytes = EXCLUDED.size_bytes,
            attachment_type = EXCLUDED.attachment_type,
            deleted_at = NULL,
            -- Committed is a one-way door for a row that is already visible.
            committed_at = coalesce(public.note_attachments.committed_at, EXCLUDED.committed_at);

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'attachment_id', v_attachment_id,
        'note_id', v_note_id,
        'object_key', v_key
    );
END;
$function$;

CREATE OR REPLACE FUNCTION public.precheck_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_mime_type TEXT,
    p_declared_size_bytes BIGINT DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_mime TEXT := lower(split_part(trim(p_mime_type), ';', 1));
    v_live_count INTEGER;
    v_user_bytes BIGINT;
    v_row public.note_attachments%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- Refuse a retired identity before the Worker reads or writes a single byte. This is a
    -- courtesy, not the boundary: a DELETE can still claim the attachment after this answer, so
    -- finalize_note_attachment_put re-checks it under the row lock and has the final say.
    SELECT * INTO v_row
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND attachment_id = v_attachment_id;

    IF FOUND AND public.attachment_delete_is_terminal(v_row) THEN
        RETURN jsonb_build_object('allowed', false, 'reason', 'terminally_deleted');
    END IF;

    IF NOT public.is_allowed_attachment_mime(v_mime) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF p_declared_size_bytes IS NOT NULL
       AND (p_declared_size_bytes < 1 OR p_declared_size_bytes > 10485760) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    SELECT count(*) INTO v_live_count
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_live_count >= 20 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF p_declared_size_bytes IS NOT NULL THEN
        v_user_bytes := notelikeus_internal.attachment_stored_bytes(v_owner, v_attachment_id);
        IF v_user_bytes + p_declared_size_bytes > 209715200 THEN
            RETURN jsonb_build_object('allowed', false);
        END IF;
    END IF;

    -- Deliberately does not report whether the attachment is already committed. That decision
    -- belongs to authorize_note_attachment_put, which runs once the real byte count is known.
    RETURN jsonb_build_object(
        'allowed', true,
        'object_key', public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id),
        'max_bytes', 10485760
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.authorize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_mime TEXT := lower(split_part(trim(p_mime_type), ';', 1));
    v_key TEXT;
    v_live_count INTEGER;
    v_user_bytes BIGINT;
    v_live public.note_attachments%ROWTYPE;
    v_row public.note_attachments%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- Refuse a retired identity before the Worker reads or writes a single byte. This is a
    -- courtesy, not the boundary: a DELETE can still claim the attachment after this answer, so
    -- finalize_note_attachment_put re-checks it under the row lock and has the final say.
    SELECT * INTO v_row
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND attachment_id = v_attachment_id;

    IF FOUND AND public.attachment_delete_is_terminal(v_row) THEN
        RETURN jsonb_build_object('allowed', false, 'reason', 'terminally_deleted');
    END IF;

    IF NOT public.is_allowed_attachment_mime(v_mime) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF p_size_bytes IS NULL OR p_size_bytes < 1 OR p_size_bytes > 10485760 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- Note must exist and must not be tombstoned
    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- Advisory quota checks
    SELECT count(*) INTO v_live_count
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
    IF v_live_count >= 20 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    v_user_bytes := notelikeus_internal.attachment_stored_bytes(v_owner, v_attachment_id);
    IF v_user_bytes + p_size_bytes > 209715200 THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    v_key := public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id);

    -- A live row for this exact identity means the bytes behind v_key are already committed.
    SELECT * INTO v_live
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND deleted_at IS NULL;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'allowed', true,
            'already_live', true,
            'object_key', v_key,
            'mime_type', v_live.mime_type,
            'size_bytes', v_live.size_bytes,
            'max_bytes', 10485760
        );
    END IF;

    RETURN jsonb_build_object(
        'allowed', true,
        'already_live', false,
        'object_key', v_key,
        'max_bytes', 10485760
    );
END;
$$;
