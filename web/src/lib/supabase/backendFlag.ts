/**
 * Pure backend-selection rules shared by the Vite client.
 *
 * Production (`notelike.web.app`) stays on Firebase unless a cutover build sets
 * `VITE_REMOTE_BACKEND=supabase` and `VITE_ALLOW_SUPABASE_PRODUCTION=true`
 * against a non-localhost Supabase URL. Ordinary users cannot switch backends.
 *
 * A Pages *staging* build may set `VITE_ALLOW_SUPABASE_STAGING=true` instead.
 * That bundle talks to Supabase only on `*.pages.dev` hosts, so the same
 * artifacts cannot cut over Firebase Hosting.
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

export function isPagesDevHost(hostname: string): boolean {
  const host = hostname.trim().toLowerCase();
  return host === 'pages.dev' || host.endsWith('.pages.dev');
}

function isTruthyFlag(value: string | undefined): boolean {
  return value === 'true' || value === '1';
}

export function resolveSupabaseBackendEnabled(input: {
  isProd: boolean;
  isE2e: boolean;
  remoteBackend?: string;
  allowProduction?: string;
  allowStaging?: string;
  supabaseUrl: string;
  hostname?: string;
}): boolean {
  if (input.remoteBackend !== 'supabase') return false;
  if (!input.isProd || input.isE2e) return true;
  if (isLocalSupabaseUrl(input.supabaseUrl)) return false;
  if (isTruthyFlag(input.allowProduction)) return true;
  return isTruthyFlag(input.allowStaging) && isPagesDevHost(input.hostname ?? '');
}
