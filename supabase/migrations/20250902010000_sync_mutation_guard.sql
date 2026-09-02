-- Harden sync mutations: block direct table writes (revision/owner forgery) and
-- strengthen tombstone resurrection guards.

CREATE OR REPLACE FUNCTION public.sync_mutation_guard()
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

CREATE TRIGGER notes_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.notes
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_mutation_guard();

CREATE TRIGGER note_tombstones_sync_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.note_tombstones
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_mutation_guard();

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
    PERFORM set_config('notelikeus.sync_mutation', '1', true);

    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;

    IF p_note_id IS DISTINCT FROM p_local_id::text THEN
        RAISE EXCEPTION 'note_id must match local_id text form' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_existing
    FROM public.notes
    WHERE owner_id = v_owner AND note_id = p_note_id
    FOR UPDATE;

    IF FOUND THEN
        IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
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
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_deleted');
    END IF;

    IF p_base_revision IS NOT NULL THEN
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

    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'server_updated_at', (extract(epoch FROM v_server_updated_at) * 1000)::bigint
    );
END;
$$;

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
    PERFORM set_config('notelikeus.sync_mutation', '1', true);

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
            RETURN jsonb_build_object('status', 'applied', 'idempotent', true);
        END IF;
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_not_found');
    END IF;

    IF p_base_revision IS NULL OR p_base_revision <> v_existing.revision THEN
        RETURN jsonb_build_object(
            'status', 'conflict',
            'current', public.note_row_to_json(v_existing)
        );
    END IF;

    DELETE FROM public.notes WHERE owner_id = v_owner AND note_id = p_note_id;

    v_new_revision := nextval('public.sync_revision_seq');
    INSERT INTO public.note_tombstones (note_id, owner_id, revision, deleted_at)
    VALUES (p_note_id, v_owner, v_new_revision, v_deleted_at)
    ON CONFLICT (owner_id, note_id) DO UPDATE
        SET revision = EXCLUDED.revision,
            deleted_at = EXCLUDED.deleted_at;

    RETURN jsonb_build_object(
        'status', 'applied',
        'revision', v_new_revision,
        'deleted_at', (extract(epoch FROM v_deleted_at) * 1000)::bigint
    );
END;
$$;
