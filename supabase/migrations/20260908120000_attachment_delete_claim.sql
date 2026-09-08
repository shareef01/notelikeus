-- Make a user-initiated attachment DELETE durable, idempotent, and recoverable.
--
-- The Worker deleted the R2 object first and only then asked the database to mark the metadata
-- deleted, without checking the answer. Three failures fell out of that order:
--
--   1. finalize_note_attachment_delete could be refused (401/403), fail (5xx), time out, or come
--      back malformed, and the Worker still answered 200 {"deleted":true}. The row stayed live
--      while its bytes were gone, so the note kept an attachment that could never be fetched.
--   2. Even when the Worker reported the failure, nothing recorded that the object had been
--      destroyed. A client that gave up left that live-row-without-an-object permanently, and no
--      sweeper could find it: list_orphaned_deleted_attachments only looks at deleted rows.
--   3. deleted_at alone did not stop restore_note from undeleting the row later, so deleting an
--      attachment, deleting its note, and restoring the note resurrected metadata whose bytes the
--      Worker had already deleted.
--
-- Deletion is now claimed before any byte is touched, exactly as the orphan sweeper claims (see
-- 20260907140000_attachment_purge_claim.sql). begin_note_attachment_delete takes the row lock,
-- records the intent, and hands back the canonical key; the Worker deletes the object; then
-- finalize_note_attachment_delete stamps the confirmation. Every step is idempotent, so a retry
-- after a failure at any point converges, and the worst outcome of an abandoned request is an
-- orphaned R2 object -- which the sweeper can now finish, rather than a live row pointing at
-- nothing.
--
-- Deploy this migration BEFORE the Worker that calls the new RPCs. A Worker calling a function
-- that does not exist yet gets PostgREST's 404 and refuses the request; the reverse order (schema
-- first) is safe because the old Worker's RPCs all still exist and still behave.

-- The owner asked for this attachment to be destroyed. Deliberately NOT purge_claimed_at: that
-- one is the sweeper's grant to remove the metadata row, and a live note's attachment must keep
-- its row so clients can still see the deletion and drop their local copy.
ALTER TABLE public.note_attachments
    ADD COLUMN IF NOT EXISTS delete_claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN public.note_attachments.delete_claimed_at IS
    'Set when the owner claimed this attachment for deletion, before its R2 object was touched. '
    'Its bytes are forfeit, so restore must not bring the row back to live.';

-- Confirmation that the canonical object is gone. Distinct from the claims above, which only say
-- deletion was granted: between the two the bytes may or may not still be in R2.
ALTER TABLE public.note_attachments
    ADD COLUMN IF NOT EXISTS object_deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN public.note_attachments.object_deleted_at IS
    'Set once the canonical R2 object for this attachment is confirmed deleted. A claimed row '
    'without this stamp may still have bytes in R2, so the sweeper retries the object delete.';

-- Drives list_unconfirmed_attachment_deletes. Both halves of the predicate are immutable.
CREATE INDEX IF NOT EXISTS note_attachments_unconfirmed_delete_idx
    ON public.note_attachments (delete_claimed_at)
    WHERE delete_claimed_at IS NOT NULL AND object_deleted_at IS NULL;

/**
 * Phase 1 of a user-initiated attachment delete: authorize it and record the intent.
 *
 * SECURITY INVOKER on purpose. The row is found by auth.uid() and the caller-supplied ids, so a
 * caller can only ever claim their own attachment, and RLS still applies. The returned object key
 * is derived from auth.uid() rather than read from the row, so a tampered object_key column can
 * never point a Worker at another owner's bytes (the Worker re-derives it and compares anyway).
 *
 * Marking deleted_at and delete_claimed_at before the bytes go is the whole point: after this
 * commits, the attachment is deleted as far as every reader is concerned, and no later failure
 * can put it back -- restore_note is redefined below to skip a claimed row.
 *
 * Idempotent: a repeated call on an already-claimed row returns the same key and does not move
 * either timestamp, so a client retrying a delete cannot corrupt the state.
 */
CREATE OR REPLACE FUNCTION public.begin_note_attachment_delete(
    p_note_id TEXT,
    p_attachment_id TEXT
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
    v_row public.note_attachments%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;
    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    -- The lock serialises this against a concurrent restore, a concurrent delete of the same
    -- attachment, and the sweeper's own claim.
    SELECT * INTO v_row
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
    FOR UPDATE;

    -- No metadata row means there is nothing this caller owns to delete. Reported the same way
    -- for "never existed" and "already purged" so neither answer enumerates anything.
    IF NOT FOUND THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        delete_claimed_at = COALESCE(delete_claimed_at, timezone('utc', now()))
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;
    PERFORM public.end_sync_mutation();

    RETURN jsonb_build_object(
        'allowed', true,
        'object_key', public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id),
        'already_claimed', v_row.delete_claimed_at IS NOT NULL,
        'object_deleted', v_row.object_deleted_at IS NOT NULL
    );
END;
$$;

/**
 * Phase 3: record that the canonical object is gone.
 *
 * Still marks deleted_at and delete_claimed_at, so an older Worker that calls only this function
 * keeps working and still ends in a consistent state. Idempotent, and it deliberately does not
 * fail when the row has already been purged -- there is nothing left to record and nothing wrong.
 */
CREATE OR REPLACE FUNCTION public.finalize_note_attachment_delete(
    p_note_id TEXT,
    p_attachment_id TEXT
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
    v_updated INTEGER := 0;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RAISE EXCEPTION 'invalid attachment or note id' USING ERRCODE = '22023';
    END IF;

    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        delete_claimed_at = COALESCE(delete_claimed_at, timezone('utc', now())),
        object_deleted_at = COALESCE(object_deleted_at, timezone('utc', now()))
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    PERFORM public.end_sync_mutation();

    RETURN jsonb_build_object(
        'allowed', true,
        'confirmed', v_updated > 0,
        'object_key', public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id)
    );
END;
$$;

/**
 * Attachments whose deletion was claimed but whose object was never confirmed deleted.
 *
 * These are abandoned deletes: the request died between claiming and confirming, so bytes may
 * still be in R2 with no live metadata pointing at them. Unlike the orphan sweep, note liveness
 * is irrelevant here -- the attachment row itself is deleted, whatever its note is doing.
 *
 * The one-hour grace keeps ordinary in-flight deletes out of the listing. A sweeper racing a live
 * request would still be harmless (both delete the same doomed object) but pointless.
 */
CREATE OR REPLACE FUNCTION public.list_unconfirmed_attachment_deletes(
    p_limit INTEGER DEFAULT 50
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 50), 100));
BEGIN
    PERFORM public.require_service_role();
    RETURN coalesce((
        SELECT jsonb_agg(jsonb_build_object(
            'owner_id', a.owner_id,
            'attachment_id', a.attachment_id,
            'note_id', a.note_id,
            'object_key', a.object_key
        ))
        FROM (
            SELECT a.owner_id, a.attachment_id, a.note_id, a.object_key
            FROM public.note_attachments a
            WHERE a.delete_claimed_at IS NOT NULL
              AND a.object_deleted_at IS NULL
              AND a.delete_claimed_at < timezone('utc', now()) - interval '1 hour'
            ORDER BY a.delete_claimed_at ASC
            LIMIT v_limit
        ) a
    ), '[]'::jsonb);
END;
$$;

/**
 * Records that a sweeper deleted the object behind a claimed row.
 *
 * Only ever stamps a row whose deletion was already claimed -- by its owner or by the orphan
 * sweeper -- so it cannot mark a live attachment as gone. It never deletes metadata: the orphan
 * sweep's own retention rules decide that.
 */
CREATE OR REPLACE FUNCTION public.confirm_attachment_object_deleted(
    p_owner_id UUID,
    p_note_id TEXT,
    p_attachment_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_note_id TEXT := trim(p_note_id);
    v_attachment_id TEXT := trim(p_attachment_id);
    v_updated INTEGER := 0;
BEGIN
    PERFORM public.require_service_role();

    IF p_owner_id IS NULL
       OR NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id)
    THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'invalid');
    END IF;

    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments
    SET object_deleted_at = COALESCE(object_deleted_at, timezone('utc', now()))
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND (delete_claimed_at IS NOT NULL OR purge_claimed_at IS NOT NULL);
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    PERFORM public.end_sync_mutation();

    IF v_updated = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_claimed');
    END IF;
    RETURN jsonb_build_object('status', 'applied');
END;
$$;

/**
 * Cheap ownership preflight for an upload, run before the Worker reads a single body byte.
 *
 * Everything here is advisory. The authoritative size, quota, liveness, and object-key checks
 * stay in finalize_note_attachment_put, which runs under the per-owner advisory lock after the
 * bytes have actually been counted; p_declared_size_bytes is the caller's Content-Length and is
 * only ever used to refuse an upload that cannot possibly fit.
 *
 * The point is that an authenticated caller who does not own the note is refused before a
 * 10 MB body is streamed into Worker memory. Every refusal this can produce,
 * authorize_note_attachment_put would produce too for a request whose declared length matches its
 * body, so moving the rejection earlier reveals nothing new about what exists.
 */
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
BEGIN
    IF v_owner IS NULL THEN
        RETURN jsonb_build_object('allowed', false);
    END IF;

    IF NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id) THEN
        RETURN jsonb_build_object('allowed', false);
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
        SELECT coalesce(sum(size_bytes), 0) INTO v_user_bytes
        FROM public.note_attachments
        WHERE owner_id = v_owner
          AND deleted_at IS NULL
          AND attachment_id <> v_attachment_id;
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

-- Restore must not resurrect an attachment the owner themselves deleted. Redefined in full
-- because the un-delete is inlined in restore_note; only the attachment UPDATE changes.
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
    v_new_revision BIGINT;
    v_server_updated_at TIMESTAMPTZ := timezone('utc', now());
BEGIN
    PERFORM public.begin_sync_mutation();

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

    PERFORM 1 FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    DELETE FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = p_note_id;

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    v_new_revision := nextval('public.sync_revision_seq');

    IF FOUND THEN
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

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

REVOKE ALL ON FUNCTION public.begin_note_attachment_delete(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.finalize_note_attachment_delete(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.precheck_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.list_unconfirmed_attachment_deletes(INTEGER)
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.confirm_attachment_object_deleted(UUID, TEXT, TEXT)
    FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.begin_note_attachment_delete(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_delete(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.precheck_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.list_unconfirmed_attachment_deletes(INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.confirm_attachment_object_deleted(UUID, TEXT, TEXT) TO service_role;
