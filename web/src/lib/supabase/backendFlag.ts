/**
 * Pure backend-selection rules shared by the Vite client.
 *
 * Production stays on Firebase unless a cutover build sets both
 * `VITE_REMOTE_BACKEND=supabase` and `VITE_ALLOW_SUPABASE_PRODUCTION=true`
 * against a non-localhost Supabase URL. Ordinary users cannot switch backends.
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

export function resolveSupabaseBackendEnabled(input: {
  isProd: boolean;
  isE2e: boolean;
  remoteBackend?: string;
  allowProduction?: string;
  supabaseUrl: string;
}): boolean {
  if (input.remoteBackend !== 'supabase') return false;
  if (!input.isProd || input.isE2e) return true;
  const allow =
    input.allowProduction === 'true' || input.allowProduction === '1';
  if (!allow) return false;
  return !isLocalSupabaseUrl(input.supabaseUrl);
}
