-- Phase 6: map legacy Firebase Auth UIDs to Supabase auth.users.id for account continuity.

CREATE TABLE public.firebase_uid_mappings (
    firebase_uid TEXT NOT NULL,
    owner_id UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc', now()),
    CONSTRAINT firebase_uid_mappings_pkey PRIMARY KEY (firebase_uid),
    CONSTRAINT firebase_uid_mappings_owner_unique UNIQUE (owner_id),
    CONSTRAINT firebase_uid_mappings_firebase_uid_nonempty CHECK (char_length(trim(firebase_uid)) > 0)
);

ALTER TABLE public.firebase_uid_mappings ENABLE ROW LEVEL SECURITY;

CREATE POLICY firebase_uid_mappings_select_own ON public.firebase_uid_mappings
    FOR SELECT TO authenticated
    USING (owner_id = auth.uid());

CREATE POLICY firebase_uid_mappings_insert_own ON public.firebase_uid_mappings
    FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

CREATE OR REPLACE FUNCTION public.link_firebase_uid(p_firebase_uid TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_uid TEXT := trim(p_firebase_uid);
BEGIN
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'not authenticated' USING ERRCODE = '28000';
    END IF;
    IF v_uid IS NULL OR char_length(v_uid) = 0 THEN
        RAISE EXCEPTION 'firebase_uid required' USING ERRCODE = '22023';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.firebase_uid_mappings
        WHERE firebase_uid = v_uid AND owner_id <> v_owner
    ) THEN
        RAISE EXCEPTION 'firebase_uid already linked to another account' USING ERRCODE = '23505';
    END IF;

    INSERT INTO public.firebase_uid_mappings (firebase_uid, owner_id)
    VALUES (v_uid, v_owner)
    ON CONFLICT (owner_id) DO UPDATE
        SET firebase_uid = EXCLUDED.firebase_uid,
            linked_at = timezone('utc', now())
    WHERE public.firebase_uid_mappings.owner_id = v_owner;

    RETURN jsonb_build_object(
        'firebase_uid', v_uid,
        'owner_id', v_owner::text
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.get_linked_firebase_uid()
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = public
AS $$
    SELECT firebase_uid
    FROM public.firebase_uid_mappings
    WHERE owner_id = auth.uid();
$$;
