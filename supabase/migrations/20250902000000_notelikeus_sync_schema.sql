-- Notelikeus Supabase backend (local development only — Phase 3).
-- Mirrors Firestore note/tombstone semantics with a monotonic revision cursor.

CREATE SEQUENCE public.sync_revision_seq START WITH 10001 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE public.notes (
    note_id TEXT NOT NULL,
    local_id BIGINT NOT NULL,
    owner_id UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT nextval('public.sync_revision_seq'),
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    client_timestamp BIGINT NOT NULL,
    color INTEGER NOT NULL,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    is_trashed BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    reminder_timestamp BIGINT,
    labels JSONB NOT NULL DEFAULT '[]'::jsonb,
    checklist JSONB NOT NULL DEFAULT '[]'::jsonb,
    server_updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT notes_pkey PRIMARY KEY (owner_id, note_id),
    CONSTRAINT notes_local_id_matches CHECK (note_id = local_id::text),
    CONSTRAINT notes_title_len CHECK (char_length(title) <= 2000),
    CONSTRAINT notes_content_len CHECK (char_length(content) <= 100000),
    CONSTRAINT notes_labels_array CHECK (jsonb_typeof(labels) = 'array'),
    CONSTRAINT notes_checklist_array CHECK (jsonb_typeof(checklist) = 'array')
);

CREATE UNIQUE INDEX notes_owner_revision_idx ON public.notes (owner_id, revision);
CREATE INDEX notes_owner_local_id_idx ON public.notes (owner_id, local_id);

CREATE TABLE public.note_tombstones (
    note_id TEXT NOT NULL,
    owner_id UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT nextval('public.sync_revision_seq'),
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT note_tombstones_pkey PRIMARY KEY (owner_id, note_id)
);

CREATE UNIQUE INDEX note_tombstones_owner_revision_idx ON public.note_tombstones (owner_id, revision);

CREATE TABLE public.sync_meta (
    owner_id UUID PRIMARY KEY REFERENCES auth.users (id) ON DELETE CASCADE,
    last_sync_at TIMESTAMPTZ,
    note_count INTEGER NOT NULL DEFAULT 0,
    platform TEXT NOT NULL DEFAULT ''
);

ALTER TABLE public.notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.note_tombstones ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_meta ENABLE ROW LEVEL SECURITY;

CREATE POLICY notes_select_own ON public.notes
    FOR SELECT TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY notes_insert_own ON public.notes
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY notes_update_own ON public.notes
    FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY notes_delete_own ON public.notes
    FOR DELETE TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY tombstones_select_own ON public.note_tombstones
    FOR SELECT TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY tombstones_insert_own ON public.note_tombstones
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY tombstones_update_own ON public.note_tombstones
    FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY tombstones_delete_own ON public.note_tombstones
    FOR DELETE TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY sync_meta_select_own ON public.sync_meta
    FOR SELECT TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY sync_meta_insert_own ON public.sync_meta
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY sync_meta_update_own ON public.sync_meta
    FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY sync_meta_delete_own ON public.sync_meta
    FOR DELETE TO authenticated
    USING (owner_id = auth.uid());

CREATE OR REPLACE FUNCTION public.note_row_to_json(p_row public.notes)
RETURNS jsonb
LANGUAGE sql
STABLE
SET search_path = public
AS $$
    SELECT jsonb_build_object(
        'type', 'note',
        'note_id', p_row.note_id,
        'local_id', p_row.local_id,
        'revision', p_row.revision,
        'title', p_row.title,
        'content', p_row.content,
        'client_timestamp', p_row.client_timestamp,
        'color', p_row.color,
        'is_pinned', p_row.is_pinned,
        'is_archived', p_row.is_archived,
        'is_trashed', p_row.is_trashed,
        'position', p_row.position,
        'reminder_timestamp', p_row.reminder_timestamp,
        'labels', p_row.labels,
        'checklist', p_row.checklist,
        'server_updated_at', (extract(epoch FROM p_row.server_updated_at) * 1000)::bigint
    );
$$;

CREATE OR REPLACE FUNCTION public.tombstone_row_to_json(p_row public.note_tombstones)
RETURNS jsonb
LANGUAGE sql
STABLE
SET search_path = public
AS $$
    SELECT jsonb_build_object(
        'type', 'tombstone',
        'note_id', p_row.note_id,
        'revision', p_row.revision,
        'deleted_at', (extract(epoch FROM p_row.deleted_at) * 1000)::bigint
    );
$$;

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

    IF p_base_revision IS NOT NULL THEN
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_not_found');
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.note_tombstones
        WHERE owner_id = v_owner AND note_id = p_note_id
    ) THEN
        RETURN jsonb_build_object('status', 'conflict', 'error', 'note_deleted');
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

CREATE OR REPLACE FUNCTION public.pull_changes(
    p_after_revision BIGINT DEFAULT 0,
    p_limit INTEGER DEFAULT 100
)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    WITH combined AS (
        SELECT revision, public.note_row_to_json(n.*) AS change
        FROM public.notes n
        WHERE n.owner_id = auth.uid() AND n.revision > COALESCE(p_after_revision, 0)
        UNION ALL
        SELECT revision, public.tombstone_row_to_json(t.*) AS change
        FROM public.note_tombstones t
        WHERE t.owner_id = auth.uid() AND t.revision > COALESCE(p_after_revision, 0)
    ),
    limited AS (
        SELECT change, revision
        FROM combined
        ORDER BY revision ASC
        LIMIT GREATEST(p_limit, 1)
    ),
    totals AS (
        SELECT count(*) AS total FROM combined
    )
    SELECT jsonb_build_object(
        'changes',
        COALESCE((SELECT jsonb_agg(change ORDER BY revision ASC) FROM limited), '[]'::jsonb),
        'has_more',
        (SELECT total > p_limit FROM totals)
    );
$$;

CREATE OR REPLACE FUNCTION public.fetch_full_snapshot()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    SELECT jsonb_build_object(
        'notes',
        COALESCE(
            jsonb_agg(public.note_row_to_json(n.*) ORDER BY n.position, n.local_id),
            '[]'::jsonb
        ),
        'tombstones',
        COALESCE(
            (
                SELECT jsonb_agg(public.tombstone_row_to_json(t.*) ORDER BY t.revision)
                FROM public.note_tombstones t
                WHERE t.owner_id = auth.uid()
            ),
            '[]'::jsonb
        ),
        'note_count',
        (SELECT count(*)::integer FROM public.notes n WHERE n.owner_id = auth.uid())
    )
    FROM public.notes n
    WHERE n.owner_id = auth.uid();
$$;

REVOKE ALL ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.apply_note_delete(TEXT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.pull_changes(BIGINT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fetch_full_snapshot() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.apply_note_change(
    TEXT, BIGINT, BIGINT, TEXT, TEXT, BIGINT, INTEGER, BOOLEAN, BOOLEAN, BOOLEAN, INTEGER, BIGINT, JSONB, JSONB
) TO authenticated;
GRANT EXECUTE ON FUNCTION public.apply_note_delete(TEXT, BIGINT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.pull_changes(BIGINT, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fetch_full_snapshot() TO authenticated;
