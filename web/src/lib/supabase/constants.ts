/** Default local Supabase anon key from `supabase start` (demo JWT). */
export const DEFAULT_LOCAL_SUPABASE_ANON_KEY =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0';

export const DEFAULT_LOCAL_SUPABASE_URL = 'http://127.0.0.1:54321';

/** Poll interval for Supabase pull_changes when realtime is not wired yet (Phase 7). */
export const SUPABASE_PULL_INTERVAL_MS = 5_000;
