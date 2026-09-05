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

/**
 * Whether an anon key is safe to ship in a browser bundle.
 *
 * Supabase issues two shapes that are easy to confuse: the public anon JWT (`eyJ…`, safe in a
 * client because RLS is what authorises it) and secret API keys (`sb_secret_…`, and `sb_…`
 * generally), which are backend credentials. A secret key pasted into a Vite env file is inlined
 * into the bundle. Refuse those so a build that cannot authenticate is preferred to one that
 * ships a secret.
 */
export function isBrowserSafeSupabaseKey(key: string): boolean {
  const trimmed = key.trim();
  if (!trimmed) return false;
  if (trimmed.toLowerCase().startsWith('sb_')) return false;
  return trimmed.startsWith('eyJ');
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
