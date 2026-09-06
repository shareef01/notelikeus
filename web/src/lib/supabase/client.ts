import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { loadSupabaseAnonKey, loadSupabaseUrl } from '@/lib/supabase/env';

export {
  isSupabaseBackendEnabled,
  loadSupabaseAnonKey,
  loadSupabaseUrl,
} from '@/lib/supabase/env';

let client: SupabaseClient | null = null;

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
