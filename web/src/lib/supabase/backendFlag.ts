/**
 * Pure Supabase configuration rules shared by the Vite client.
 *
 * Supabase is the only remote backend. A production build must point at a hosted
 * project URL with a browser-safe anon/publishable key.
 */

export function isLocalSupabaseUrl(url: string): boolean {
  const trimmed = url.trim();
  if (!trimmed) return true;
  try {
    const host = new URL(trimmed).hostname.toLowerCase();
    return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host.endsWith('.local');
  } catch {
    return true;
  }
}

/** Prefix of Supabase's new public client key, the replacement for the legacy anon JWT (D31). */
export const PUBLISHABLE_KEY_PREFIX = 'sb_publishable_';

/** The two client key kinds a build may ship, or `null` for anything else. */
export type SupabaseClientKeyKind = 'publishable' | 'legacy-anon-jwt';

/**
 * Role claimed by a legacy JWT key, or `null` when the payload cannot be read. Only used to refuse
 * a `service_role` JWT; the signature is not checked, because the client never trusts the claim.
 */
function legacyJwtRole(key: string): string | null {
  const payload = key.split('.')[1];
  if (!payload) return null;
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const claims: unknown = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')));
    if (claims && typeof claims === 'object' && 'role' in claims) {
      const role = (claims as { role: unknown }).role;
      return typeof role === 'string' ? role : null;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Which client key a value is, or `null` when it must not ship in a browser bundle.
 *
 * Supabase issues public client keys in two shapes: the publishable key (`sb_publishable_…`) and
 * the legacy anon JWT (`eyJ…`). Both are safe in a client because RLS is what authorises a request.
 * Secret API keys (`sb_secret_…`, and every other `sb_…`) and a legacy `service_role` JWT are
 * backend credentials, and a Vite env value is inlined into the bundle. Refuse those, so a build
 * that cannot authenticate is preferred to one that ships a secret.
 */
export function supabaseClientKeyKind(key: string): SupabaseClientKeyKind | null {
  const trimmed = key.trim();
  if (trimmed.startsWith(PUBLISHABLE_KEY_PREFIX) && trimmed.length > PUBLISHABLE_KEY_PREFIX.length) {
    return 'publishable';
  }
  if (trimmed.toLowerCase().startsWith('sb_')) return null;
  if (!trimmed.startsWith('eyJ')) return null;
  return legacyJwtRole(trimmed) === 'service_role' ? null : 'legacy-anon-jwt';
}

/** Whether a key is safe to ship in a browser bundle. See {@link supabaseClientKeyKind}. */
export function isBrowserSafeSupabaseKey(key: string): boolean {
  return supabaseClientKeyKind(key) !== null;
}

export function isSupabaseConfigured(input: {
  isProd: boolean;
  isE2e: boolean;
  supabaseUrl: string;
  anonKey: string;
}): boolean {
  if (!isBrowserSafeSupabaseKey(input.anonKey)) return false;
  if (!input.supabaseUrl.trim()) return false;
  if (input.isProd && !input.isE2e && isLocalSupabaseUrl(input.supabaseUrl)) return false;
  return true;
}
