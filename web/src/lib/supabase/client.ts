import { createClient, type SupabaseClient } from '@supabase/supabase-js';
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
 * Dev-only flag. Production builds always use Firebase regardless of env.
 * Phase 5 will wire Supabase Auth; until then the client must already hold a session JWT.
 */
export function isSupabaseBackendEnabled(): boolean {
  if (import.meta.env.PROD && !import.meta.env.VITE_E2E) return false;
  return import.meta.env.VITE_REMOTE_BACKEND === 'supabase';
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
