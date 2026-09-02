/** Default local Supabase anon key from `supabase start` (demo JWT). */
export const DEFAULT_LOCAL_SUPABASE_ANON_KEY =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0';

export const DEFAULT_LOCAL_SUPABASE_URL = 'http://127.0.0.1:54321';

/** Debounce window before pulling after a realtime postgres_changes burst. */
export const SUPABASE_PULL_DEBOUNCE_MS = 300;

/** Slow polling fallback when the Realtime channel is unavailable. */
export const SUPABASE_PULL_FALLBACK_MS = 30_000;
