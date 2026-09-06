import type { User as SupabaseUser } from '@supabase/supabase-js';

/** Platform-agnostic signed-in user for the web auth store. */
export interface AuthUser {
  uid: string;
  email: string | null;
  displayName: string | null;
  /** Derived from Supabase identities / app_metadata. Not equivalent to being signed in. */
  isGoogleAccount?: boolean;
}

function isGoogleAuthUser(user: SupabaseUser): boolean {
  if (user.app_metadata?.provider === 'google') return true;
  const providers = user.app_metadata?.providers;
  if (Array.isArray(providers) && providers.includes('google')) return true;
  return user.identities?.some((identity) => identity.provider === 'google') === true;
}

export function authUserFromSupabase(user: SupabaseUser): AuthUser {
  const metadata = user.user_metadata as { full_name?: string; name?: string } | undefined;
  return {
    uid: user.id,
    email: user.email ?? null,
    displayName: metadata?.full_name ?? metadata?.name ?? null,
    isGoogleAccount: isGoogleAuthUser(user),
  };
}
