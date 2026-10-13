-- FU-4: stop exposing the sync-mutation guard helpers over the Data API.
--
-- `begin_sync_mutation()` and `end_sync_mutation()` were granted to `authenticated` in `public`,
-- which is an exposed schema, so PostgREST published them as RPCs (FU-4). They were never
-- exploitable — the flag they set is transaction-local and PostgREST gives each request its own
-- transaction, so a caller could not pair one with a table write — but a helper whose only job is
-- to open a write gate has no business being addressable from outside the database.
--
-- They cannot simply be revoked: every RPC here runs `SECURITY INVOKER`, so the calling role has to
-- hold EXECUTE on the helper itself. Moving them to a schema PostgREST does not expose closes the
-- surface and keeps the RPCs working, at the cost of recreating the callers with a qualified
-- reference. That is what follows, generated from each caller's own latest definition so no body is
-- retyped.
--
-- What changes and what does not:
--   * `notelikeus_internal` is not in `[api] schemas` in supabase/config.toml (which lists only
--     `public` and `graphql_public`), so nothing in it is reachable as an RPC.
--   * `authenticated` and `service_role` keep USAGE on the schema and EXECUTE on the helpers,
--     because the SECURITY INVOKER callers and the service-role sweeps need them.
--   * Every RPC keeps its signature, its body and its grants; only the three references change.
--   * The guard triggers are repointed, then the `public` originals are dropped. A dependency that
--     was missed would fail the drop loudly rather than leave a dangling trigger.
--
-- Rollback: re-create the three helpers in `public`, repoint the triggers, `DROP SCHEMA
-- notelikeus_internal` and restore the callers from the migrations as they were. The generation
-- bookkeeping this touches is unchanged, so no data migration is involved either way.

CREATE SCHEMA IF NOT EXISTS notelikeus_internal;

COMMENT ON SCHEMA notelikeus_internal IS
    'Internal helpers that must not be addressable through PostgREST. Not listed in [api] schemas.';

-- Reachable by the roles whose SECURITY INVOKER functions call into it, and by nothing else.
REVOKE ALL ON SCHEMA notelikeus_internal FROM PUBLIC;
GRANT USAGE ON SCHEMA notelikeus_internal TO authenticated, service_role;

-- Moved verbatim from `public` (originally 20250902010000_sync_mutation_guard.sql).
CREATE OR REPLACE FUNCTION notelikeus_internal.sync_mutation_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    IF current_setting('notelikeus.sync_mutation', true) IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'direct table mutation not allowed' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

-- Moved verbatim from `public` (originally 20250904000000_mapping_and_attachment_guards.sql).
CREATE OR REPLACE FUNCTION notelikeus_internal.begin_sync_mutation()
RETURNS void
LANGUAGE sql
SET search_path = public
AS $$ SELECT set_config('notelikeus.sync_mutation', '1', true); $$;

-- Moved verbatim from `public` (originally 20250904000000_mapping_and_attachment_guards.sql).
CREATE OR REPLACE FUNCTION notelikeus_internal.end_sync_mutation()
RETURNS void
LANGUAGE sql
SET search_path = public
AS $$ SELECT set_config('notelikeus.sync_mutation', '', true); $$;

REVOKE ALL ON FUNCTION notelikeus_internal.begin_sync_mutation() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.end_sync_mutation() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.sync_mutation_guard() FROM PUBLIC, anon;
-- `authenticated` executes them through the SECURITY INVOKER RPCs and the guard triggers;
-- `service_role` executes them through the sweep functions, which run as service_role.
GRANT EXECUTE ON FUNCTION notelikeus_internal.begin_sync_mutation() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.end_sync_mutation() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.sync_mutation_guard() TO authenticated, service_role;

-- ---------------------------------------------------------------------------------------------
-- The callers, recreated against the new schema. Same signatures, same bodies, same grants.
-- ---------------------------------------------------------------------------------------------

-- Unchanged apart from the helper reference (last defined in 20260910000000_attachment_commitment.sql).
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

-- Unchanged apart from the helper reference (last defined in 20260906120000_restore_payload_and_lookup.sql).
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

-- Unchanged apart from the helper reference (last defined in 20260908120000_attachment_delete_claim.sql).
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

    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        delete_claimed_at = COALESCE(delete_claimed_at, timezone('utc', now()))
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;
    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object(
        'allowed', true,
        'object_key', public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id),
        'already_claimed', v_row.delete_claimed_at IS NOT NULL,
        'object_deleted', v_row.object_deleted_at IS NOT NULL
    );
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260907140000_attachment_purge_claim.sql).
CREATE OR REPLACE FUNCTION public.claim_orphaned_attachment_for_delete(
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
    v_row public.note_attachments%ROWTYPE;
BEGIN
    PERFORM public.require_service_role();

    IF p_owner_id IS NULL
       OR NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id)
    THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'invalid');
    END IF;

    -- The lock is the whole point: restore updates this same row, so one of the two waits.
    SELECT * INTO v_row
    FROM public.note_attachments
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'missing');
    END IF;

    -- A live note is the most fundamental reason to refuse, and the most useful one to report,
    -- so it is checked first. A restore that got here before the claim leaves the note live.
    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'note_live');
    END IF;

    -- Note gone but the attachment is not deleted: nothing to sweep. Distinct from 'note_live',
    -- and not called 'restored', which would misdescribe a row that was never deleted at all.
    IF v_row.deleted_at IS NULL THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'not_deleted');
    END IF;

    IF v_row.deleted_at >= timezone('utc', now()) - interval '24 hours' THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'retention');
    END IF;

    IF v_row.purge_requested_at IS NULL AND NOT EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = p_owner_id AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('claimed', false, 'reason', 'not_tombstoned');
    END IF;

    -- note_attachments is behind a mutation guard, so every write has to declare itself the
    -- same way the other RPCs do. Without this the claim raised "direct table mutation not
    -- allowed" and no attachment could ever be claimed for deletion.
    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.note_attachments
    SET purge_claimed_at = COALESCE(purge_claimed_at, timezone('utc', now()))
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;
    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object(
        'claimed', true,
        'object_key', public.expected_attachment_object_key(p_owner_id, v_note_id, v_attachment_id)
    );
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20261012000000_provisional_attachment_sweep.sql).
CREATE OR REPLACE FUNCTION public.claim_orphaned_provisional_attachments(
    p_limit INTEGER DEFAULT 50,
    p_min_age INTERVAL DEFAULT interval '14 days'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_limit INTEGER := GREATEST(1, LEAST(COALESCE(p_limit, 50), 100));
    -- Never shorter than a day, whatever the caller asks: this deletes user data.
    v_min_age INTERVAL := GREATEST(COALESCE(p_min_age, interval '14 days'), interval '1 day');
    v_now TIMESTAMPTZ := timezone('utc', now());
    v_claimed jsonb;
BEGIN
    PERFORM public.require_service_role();
    PERFORM notelikeus_internal.begin_sync_mutation();

    -- FOR UPDATE ... SKIP LOCKED: a row a note commit is promoting right now is skipped rather than
    -- waited on, and a row whose promotion committed after this snapshot is re-checked against its new
    -- version, so committed_at IS NULL excludes it.
    WITH candidates AS (
        SELECT a.owner_id, a.attachment_id
        FROM public.note_attachments a
        WHERE a.committed_at IS NULL
          AND a.deleted_at IS NULL
          AND a.delete_claimed_at IS NULL
          AND a.purge_claimed_at IS NULL
          AND a.created_at < v_now - v_min_age
          AND EXISTS (
              SELECT 1 FROM public.notes n
              WHERE n.owner_id = a.owner_id
                AND n.note_id = a.note_id
                AND n.server_updated_at > a.created_at
                AND n.server_updated_at < v_now - v_min_age
          )
        ORDER BY a.created_at ASC
        LIMIT v_limit
        FOR UPDATE OF a SKIP LOCKED
    ),
    claimed AS (
        UPDATE public.note_attachments a
        SET deleted_at = v_now,
            delete_claimed_at = v_now
        FROM candidates c
        WHERE a.owner_id = c.owner_id
          AND a.attachment_id = c.attachment_id
        RETURNING a.owner_id, a.note_id, a.attachment_id, a.object_key
    )
    SELECT coalesce(
        jsonb_agg(jsonb_build_object(
            'owner_id', owner_id,
            'note_id', note_id,
            'attachment_id', attachment_id,
            'object_key', object_key
        )),
        '[]'::jsonb
    )
    INTO v_claimed
    FROM claimed;

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN v_claimed;
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260905000000_remove_firebase_compatibility.sql).
CREATE OR REPLACE FUNCTION public.clear_note_tombstone(p_note_id TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_deleted INTEGER := 0;
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    DELETE FROM public.note_tombstones
    WHERE owner_id = v_owner AND note_id = trim(p_note_id);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object('cleared', v_deleted > 0, 'note_id', trim(p_note_id));
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260908120000_attachment_delete_claim.sql).
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

    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.note_attachments
    SET object_deleted_at = COALESCE(object_deleted_at, timezone('utc', now()))
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND (delete_claimed_at IS NOT NULL OR purge_claimed_at IS NOT NULL);
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    IF v_updated = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_claimed');
    END IF;
    RETURN jsonb_build_object('status', 'applied');
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20261013000000_account_wipe_generation.sql).
CREATE OR REPLACE FUNCTION public.delete_all_user_cloud_data()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_notes INTEGER := 0;
    v_tombstones INTEGER := 0;
    v_attachments INTEGER := 0;
    v_generation BIGINT := 0;
    v_object_keys TEXT[] := ARRAY[]::text[];
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    INSERT INTO public.account_sync_state (owner_id, wipe_generation, wiped_at)
    VALUES (v_owner, 1, timezone('utc', now()))
    ON CONFLICT (owner_id) DO UPDATE
        SET wipe_generation = public.account_sync_state.wipe_generation + 1,
            wiped_at = timezone('utc', now())
    RETURNING wipe_generation INTO v_generation;

    PERFORM public.present_wipe_generation(v_generation);

    -- Every object the user owns, whether or not it was already soft-deleted. The previous
    -- `deleted_at IS NULL` filter is what stranded already-deleted attachments in R2.
    SELECT COALESCE(array_agg(object_key), ARRAY[]::text[])
    INTO v_object_keys
    FROM public.note_attachments
    WHERE owner_id = v_owner;

    -- Marked, not destroyed: the Worker still has to authorize each DELETE against this row,
    -- and the sweeper still has to be able to find it if the client never comes back.
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        purge_requested_at = COALESCE(purge_requested_at, timezone('utc', now()))
    WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_attachments = ROW_COUNT;

    DELETE FROM public.notes WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_notes = ROW_COUNT;

    DELETE FROM public.note_tombstones WHERE owner_id = v_owner;
    GET DIAGNOSTICS v_tombstones = ROW_COUNT;

    DELETE FROM public.sync_meta WHERE owner_id = v_owner;

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'notes_deleted', v_notes,
        'tombstones_deleted', v_tombstones,
        'attachments_deleted', v_attachments,
        'attachment_object_keys', to_jsonb(v_object_keys),
        'wipe_generation', v_generation
    );
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20250904000000_mapping_and_attachment_guards.sql).
CREATE OR REPLACE FUNCTION public.delete_note_attachment(
    p_attachment_id TEXT,
    p_note_id TEXT
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    UPDATE public.note_attachments
    SET deleted_at = timezone('utc', now())
    WHERE owner_id = v_owner
      AND attachment_id = trim(p_attachment_id)
      AND note_id = trim(p_note_id)
      AND deleted_at IS NULL;

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'deleted', found,
        'attachment_id', trim(p_attachment_id)
    );
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260907130000_cloud_wipe_attachment_lifecycle.sql).
CREATE OR REPLACE FUNCTION public.finalize_cloud_wipe()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_purged INTEGER := 0;
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    PERFORM notelikeus_internal.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = v_owner
      AND purge_requested_at IS NOT NULL;
    GET DIAGNOSTICS v_purged = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object('status', 'applied', 'attachments_purged', v_purged);
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260908120000_attachment_delete_claim.sql).
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

    PERFORM notelikeus_internal.begin_sync_mutation();
    UPDATE public.note_attachments
    SET deleted_at = COALESCE(deleted_at, timezone('utc', now())),
        delete_claimed_at = COALESCE(delete_claimed_at, timezone('utc', now())),
        object_deleted_at = COALESCE(object_deleted_at, timezone('utc', now()))
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id;
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object(
        'allowed', true,
        'confirmed', v_updated > 0,
        'object_key', public.expected_attachment_object_key(v_owner, v_note_id, v_attachment_id)
    );
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260910000000_attachment_commitment.sql).
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

-- Unchanged apart from the helper reference (last defined in 20260906140000_restore_clears_attachment_deletes.sql).
CREATE OR REPLACE FUNCTION public.purge_deleted_note_attachment(
    p_attachment_id TEXT,
    p_note_id TEXT
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
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.notes
        WHERE owner_id = v_owner AND note_id = v_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'note_live');
    END IF;

    PERFORM notelikeus_internal.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = v_owner
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND deleted_at IS NOT NULL;
    PERFORM notelikeus_internal.end_sync_mutation();

    RETURN jsonb_build_object('status', 'applied');
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260907140000_attachment_purge_claim.sql).
CREATE OR REPLACE FUNCTION public.purge_orphaned_deleted_attachment(
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
    v_deleted INTEGER := 0;
BEGIN
    PERFORM public.require_service_role();

    IF p_owner_id IS NULL
       OR NOT public.is_valid_attachment_path_id(v_note_id)
       OR NOT public.is_valid_attachment_path_id(v_attachment_id)
    THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'invalid');
    END IF;

    PERFORM notelikeus_internal.begin_sync_mutation();
    DELETE FROM public.note_attachments
    WHERE owner_id = p_owner_id
      AND note_id = v_note_id
      AND attachment_id = v_attachment_id
      AND purge_claimed_at IS NOT NULL;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    PERFORM notelikeus_internal.end_sync_mutation();

    IF v_deleted = 0 THEN
        RETURN jsonb_build_object('status', 'skipped', 'reason', 'not_claimed');
    END IF;

    RETURN jsonb_build_object('status', 'applied');
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260906130000_attachment_authorization.sql).
CREATE OR REPLACE FUNCTION public.register_note_attachment(
    p_attachment_id TEXT,
    p_note_id TEXT,
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
BEGIN
    PERFORM notelikeus_internal.begin_sync_mutation();

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
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

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'attachment_id', v_attachment_id,
        'note_id', v_note_id,
        'object_key', v_key
    );
END;
$$;

-- Unchanged apart from the helper reference (last defined in 20260908120000_attachment_delete_claim.sql).
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

    PERFORM notelikeus_internal.end_sync_mutation();
    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The guard triggers, repointed before the functions they call are dropped.
-- ---------------------------------------------------------------------------------------------

DROP TRIGGER IF EXISTS notes_sync_mutation_guard ON public.notes;
CREATE TRIGGER notes_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.notes
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.sync_mutation_guard();

DROP TRIGGER IF EXISTS note_tombstones_sync_mutation_guard ON public.note_tombstones;
CREATE TRIGGER note_tombstones_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.note_tombstones
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.sync_mutation_guard();

DROP TRIGGER IF EXISTS note_attachments_sync_mutation_guard ON public.note_attachments;
CREATE TRIGGER note_attachments_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.note_attachments
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.sync_mutation_guard();

DROP TRIGGER IF EXISTS account_sync_state_sync_mutation_guard ON public.account_sync_state;
CREATE TRIGGER account_sync_state_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.account_sync_state
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.sync_mutation_guard();

-- Only now: nothing may still reference the exposed copies.
DROP FUNCTION public.begin_sync_mutation();
DROP FUNCTION public.end_sync_mutation();
DROP FUNCTION public.sync_mutation_guard();
