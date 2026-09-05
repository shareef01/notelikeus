import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { isSupabaseConfigured } from '@/lib/supabase/backendFlag';
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

/** True when this build has a usable Supabase project (hosted in production, local in dev/e2e). */
export function isSupabaseBackendEnabled(): boolean {
  return isSupabaseConfigured({
    isProd: import.meta.env.PROD,
    isE2e: Boolean(import.meta.env.VITE_E2E),
    supabaseUrl: loadSupabaseUrl(),
    anonKey: loadSupabaseAnonKey(),
  });
}

export function getSupabaseClient(): SupabaseClient {
  if (!client) {
    client = createClient(loadSupabaseUrl(), loadSupabaseAnonKey(), {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        // Manual exchange in completeSupabaseOAuthRedirect(). Leaving this true
        // races that call: createClient starts _initialize() which also consumes
        // ?code=, so the PKCE grant can be used twice and the second attempt fails.
        detectSessionInUrl: false,
        flowType: 'pkce',
      },
    });
  }
  return client;
}

/** Test hook — replaces the singleton client. */
export function setSupabaseClientForTests(next: SupabaseClient | null): void {
  client = next;
}
