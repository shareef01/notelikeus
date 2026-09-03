import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { resolveSupabaseBackendEnabled } from '@/lib/supabase/backendFlag';
import {
  DEFAULT_LOCAL_SUPABASE_ANON_KEY,
  DEFAULT_LOCAL_SUPABASE_URL,
} from '@/lib/supabase/constants';

let client: SupabaseClient | null = null;

export function loadSupabaseUrl(): string {
  return import.meta.env.VITE_SUPABASE_URL?.trim() || DEFAULT_LOCAL_SUPABASE_URL;
}

export function loadSupabaseAnonKey(): string {
  return import.meta.env.VITE_SUPABASE_ANON_KEY?.trim() || DEFAULT_LOCAL_SUPABASE_ANON_KEY;
}

/**
 * Firebase remains the production default.
 * Supabase is selected in development when `VITE_REMOTE_BACKEND=supabase`.
 * A production cutover build also needs `VITE_ALLOW_SUPABASE_PRODUCTION=true`
 * and a non-localhost `VITE_SUPABASE_URL`. A Pages staging build may set
 * `VITE_ALLOW_SUPABASE_STAGING=true` instead (runtime-gated to `*.pages.dev`).
 */
export function isSupabaseBackendEnabled(): boolean {
  return resolveSupabaseBackendEnabled({
    isProd: import.meta.env.PROD,
    isE2e: Boolean(import.meta.env.VITE_E2E),
    remoteBackend: import.meta.env.VITE_REMOTE_BACKEND,
    allowProduction: import.meta.env.VITE_ALLOW_SUPABASE_PRODUCTION,
    allowStaging: import.meta.env.VITE_ALLOW_SUPABASE_STAGING,
    supabaseUrl: loadSupabaseUrl(),
    hostname: typeof window !== 'undefined' ? window.location.hostname : '',
  });
}

export function getSupabaseClient(): SupabaseClient {
  if (!client) {
    client = createClient(loadSupabaseUrl(), loadSupabaseAnonKey(), {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
      },
    });
  }
  return client;
}

/** Test hook — replaces the singleton client. */
export function setSupabaseClientForTests(next: SupabaseClient | null): void {
  client = next;
}
