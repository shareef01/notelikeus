import type { User as SupabaseUser } from '@supabase/supabase-js';

/** Platform-agnostic signed-in user for the web auth store. */
export interface AuthUser {
  uid: string;
  email: string | null;
  displayName: string | null;
}

export function authUserFromSupabase(user: SupabaseUser): AuthUser {
  const metadata = user.user_metadata as { full_name?: string; name?: string } | undefined;
  return {
    uid: user.id,
    email: user.email ?? null,
    displayName: metadata?.full_name ?? metadata?.name ?? null,
  };
}
