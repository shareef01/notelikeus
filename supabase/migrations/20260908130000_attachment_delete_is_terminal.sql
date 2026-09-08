-- Make an owner-claimed attachment deletion terminal for that attachment identity.
--
-- 20260908120000 established that a claimed deletion is final and taught restore_note to skip
-- claimed rows. It missed the other way back to live: finalize_note_attachment_put ends in
-- INSERT ... ON CONFLICT (owner_id, attachment_id) DO UPDATE SET ... deleted_at = NULL, which
-- happily revives a row carrying delete_claimed_at, purge_claimed_at, or object_deleted_at.
-- Reproduced against the applied schema, both sequentially and with two concurrent sessions:
--
--   * upload attA, delete attA, re-PUT attA  ->  deleted_at NULL with delete_claimed_at AND
--     object_deleted_at set: a live attachment whose bytes the Worker already destroyed, and one
--     the unconfirmed-delete sweep will never revisit because its object was already confirmed
--     gone. restore_note refuses to produce this state; the PUT finalizer produced it anyway.
--   * a DELETE claim committing between a PUT's authorization and its finalization resurrects
--     the identity the claim had just retired. (The reverse order was already correct: the claim
--     waits on the insert's row lock and then wins.)
--
-- Two layers, because one of them is a guarantee and the other is a usable answer:
--
--   1. A CHECK constraint, so no function present or future -- including register_note_attachment,
--      which still INSERTs ... ON CONFLICT and is only out of reach today because its grants were
--      revoked -- can leave a live row carrying a deletion claim.
--   2. finalize_note_attachment_put refuses under the conflict row's own lock and reports
--      'terminally_deleted' as a value. The Worker has written bytes by then, and it may only
--      delete them again on an unambiguous answer: an exception, a timeout, and an unreachable
--      database look alike, and one of them means a live attachment still needs those bytes.
--
-- Clearing the claim columns during a PUT was the other option and is the wrong one: it would let
-- a request resurrect an object another request has already been authorized to destroy.

/**
 * Whether an attachment row's deletion is terminal, i.e. its identity may never be live again.
 *
 * The two claims are the invariant the CHECK constraint below enforces. object_deleted_at is
 * treated as terminal here as well, defensively: nothing can set it today without a claim also
 * being set, but a row whose object is confirmed destroyed must not come back live even if some
 * future path manages it.
 */
CREATE OR REPLACE FUNCTION public.attachment_delete_is_terminal(p_row public.note_attachments)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
    SELECT p_row.delete_claimed_at IS NOT NULL
        OR p_row.purge_claimed_at IS NOT NULL
        OR p_row.object_deleted_at IS NOT NULL;
$$;

-- Repair before constraining. A database that ran the pre-20260908120000 code could already hold
-- a live row carrying purge_claimed_at: the orphan sweeper claimed the attachment, and a later
-- re-upload of the same id revived it through the same ON CONFLICT path this migration closes.
-- Its bytes are gone or going, so the row is already wrong; marking it deleted matches what is
-- actually in R2 and is what the constraint would otherwise reject on the row's next write.
-- delete_claimed_at and object_deleted_at were introduced yesterday and are still NULL
-- everywhere, so this can only touch the purge-claimed case.
DO $$
DECLARE
    v_repaired INTEGER := 0;
BEGIN
    PERFORM public.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = timezone('utc', now())
    WHERE deleted_at IS NULL
      AND (delete_claimed_at IS NOT NULL
           OR purge_claimed_at IS NOT NULL
           OR object_deleted_at IS NOT NULL);
    GET DIAGNOSTICS v_repaired = ROW_COUNT;
    PERFORM public.end_sync_mutation();
    IF v_repaired > 0 THEN
        RAISE NOTICE 'Re-marked % live attachment row(s) whose deletion had been claimed', v_repaired;
    END IF;
END;
$$;

-- The structural guarantee. Deliberately covers only the two claims: those are the states a
-- claim-holder acts on, and confining the constraint to them means restore_note can never be
-- turned into an error by a row it would otherwise simply skip.
ALTER TABLE public.note_attachments
    DROP CONSTRAINT IF EXISTS note_attachments_claimed_delete_is_terminal;

ALTER TABLE public.note_attachments
    ADD CONSTRAINT note_attachments_claimed_delete_is_terminal CHECK (
        deleted_at IS NOT NULL
        OR (delete_claimed_at IS NULL AND purge_claimed_at IS NULL)
    );

CREATE OR REPLACE FUNCTION public.finalize_note_attachment_put(
    p_note_id TEXT,
    p_attachment_id TEXT,
    p_object_key TEXT,
    p_mime_type TEXT,
    p_size_bytes BIGINT,
    p_attachment_type TEXT DEFAULT 'image'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
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
    PERFORM public.begin_sync_mutation();


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
        PERFORM public.end_sync_mutation();
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

    SELECT coalesce(sum(size_bytes), 0) INTO v_user_bytes
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
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
        deleted_at
    )
    VALUES (
        v_attachment_id,
        v_owner,
        v_note_id,
        v_key,
        v_mime,
        p_size_bytes,
        v_type,
        NULL
    )
    ON CONFLICT (owner_id, attachment_id) DO UPDATE
        SET note_id = EXCLUDED.note_id,
            object_key = EXCLUDED.object_key,
            mime_type = EXCLUDED.mime_type,
            size_bytes = EXCLUDED.size_bytes,
            attachment_type = EXCLUDED.attachment_type,
            deleted_at = NULL;

    PERFORM public.end_sync_mutation();
    RETURN jsonb_build_object(
        'attachment_id', v_attachment_id,
        'note_id', v_note_id,
        'object_key', v_key
    );
END;
$$;
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

    SELECT coalesce(sum(size_bytes), 0) INTO v_user_bytes
    FROM public.note_attachments
    WHERE owner_id = v_owner
      AND deleted_at IS NULL
      AND attachment_id <> v_attachment_id;
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

REVOKE ALL ON FUNCTION public.attachment_delete_is_terminal(public.note_attachments)
    FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.precheck_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.attachment_delete_is_terminal(public.note_attachments) TO authenticated;
GRANT EXECUTE ON FUNCTION public.precheck_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.authorize_note_attachment_put(TEXT, TEXT, TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_note_attachment_put(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT) TO authenticated;
