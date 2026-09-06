-- Empty libraries used to make fetch_full_snapshot return SQL NULL: the outer FROM notes
-- matched zero rows, so tombstones were dropped too. A second device could then re-upload
-- deleted notes. Build the object from scalar subqueries so zero notes still returns JSON.
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
            (
                SELECT jsonb_agg(public.note_row_to_json(n.*) ORDER BY n.position, n.local_id)
                FROM public.notes n
                WHERE n.owner_id = auth.uid()
            ),
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
    );
$$;

REVOKE ALL ON FUNCTION public.fetch_full_snapshot() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_full_snapshot() TO authenticated;
