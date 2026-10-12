-- Reports R2 objects that no attachment row references. Read-only: it deletes nothing.
--
-- Since F-08, the Worker keeps an upload's bytes when finalization fails ambiguously (a timeout, a
-- 5xx, a lost response), because the row may have committed. When it did not, and the client never
-- retries, the bytes stay in R2 with no row pointing at them: a storage cost, not data loss. Nothing
-- can find those objects from the database side, so the Worker lists R2 and asks which keys are
-- unreferenced. This function answers that, so the leak can be measured before anyone decides
-- whether a deleting sweep is worth its risks.
--
-- "Referenced" means any row at all for the key -- live, provisional, deleted or claimed. Rows in
-- those states already have their own sweeps; only keys with no row are reported.

CREATE OR REPLACE FUNCTION public.find_unreferenced_attachment_objects(p_object_keys TEXT[])
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
STABLE
SET search_path = public
AS $$
BEGIN
    PERFORM public.require_service_role();
    IF coalesce(array_length(p_object_keys, 1), 0) > 1000 THEN
        RAISE EXCEPTION 'at most 1000 object keys per call' USING ERRCODE = '22023';
    END IF;
    RETURN coalesce((
        SELECT jsonb_agg(k ORDER BY k)
        FROM unnest(p_object_keys) AS k
        WHERE NOT EXISTS (
            SELECT 1 FROM public.note_attachments a WHERE a.object_key = k
        )
    ), '[]'::jsonb);
END;
$$;

REVOKE ALL ON FUNCTION public.find_unreferenced_attachment_objects(TEXT[])
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.find_unreferenced_attachment_objects(TEXT[]) TO service_role;
