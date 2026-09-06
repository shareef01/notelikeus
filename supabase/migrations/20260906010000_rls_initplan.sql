-- Supabase advisor auth_rls_initplan: bare auth.uid() in RLS is re-evaluated per row.
-- Wrapping it in (SELECT …) makes Postgres treat it as an InitPlan (once per query).
-- Semantics are unchanged: owner_id must still equal the JWT user.

ALTER POLICY notes_select_own ON public.notes
    USING (owner_id = (SELECT auth.uid()));
ALTER POLICY notes_insert_own ON public.notes
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY notes_update_own ON public.notes
    USING (owner_id = (SELECT auth.uid()))
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY notes_delete_own ON public.notes
    USING (owner_id = (SELECT auth.uid()));

ALTER POLICY tombstones_select_own ON public.note_tombstones
    USING (owner_id = (SELECT auth.uid()));
ALTER POLICY tombstones_insert_own ON public.note_tombstones
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY tombstones_update_own ON public.note_tombstones
    USING (owner_id = (SELECT auth.uid()))
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY tombstones_delete_own ON public.note_tombstones
    USING (owner_id = (SELECT auth.uid()));

ALTER POLICY sync_meta_select_own ON public.sync_meta
    USING (owner_id = (SELECT auth.uid()));
ALTER POLICY sync_meta_insert_own ON public.sync_meta
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY sync_meta_update_own ON public.sync_meta
    USING (owner_id = (SELECT auth.uid()))
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY sync_meta_delete_own ON public.sync_meta
    USING (owner_id = (SELECT auth.uid()));

ALTER POLICY note_attachments_select_own ON public.note_attachments
    USING (owner_id = (SELECT auth.uid()));
ALTER POLICY note_attachments_insert_own ON public.note_attachments
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY note_attachments_update_own ON public.note_attachments
    USING (owner_id = (SELECT auth.uid()))
    WITH CHECK (owner_id = (SELECT auth.uid()));
ALTER POLICY note_attachments_delete_own ON public.note_attachments
    USING (owner_id = (SELECT auth.uid()));
