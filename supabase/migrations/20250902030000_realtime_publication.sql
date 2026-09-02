-- Phase 7: expose notes + tombstones to Supabase Realtime (RLS-filtered per subscriber).

ALTER PUBLICATION supabase_realtime ADD TABLE public.notes;
ALTER PUBLICATION supabase_realtime ADD TABLE public.note_tombstones;
