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

/** Bare hostname labels only — no scheme, port, path, credentials or query. */
const HOSTNAME_PATTERN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$/;

export function isPagesDevHost(hostname: string): boolean {
  const host = hostname.trim().toLowerCase();
  // `window.location.hostname` is always a bare host, but this gate decides whether a production
  // bundle talks to Supabase, so it validates the shape rather than trusting its caller: a
  // suffix test alone would accept `evil.example/x.pages.dev` from any other call site.
  if (!HOSTNAME_PATTERN.test(host)) return false;
  return host === 'pages.dev' || host.endsWith('.pages.dev');
}

function isTruthyFlag(value: string | undefined): boolean {
  return value === 'true' || value === '1';
}

/**
 * Whether an anon key is safe to ship in a browser bundle.
 *
 * Supabase issues two shapes that are easy to confuse when wiring staging up: the public anon JWT
 * (`eyJ…`, safe in a client because RLS is what authorises it) and the newer secret API keys
 * (`sb_secret_…`, and `sb_…` generally), which are backend credentials. A secret key pasted into
 * `web/.env.staging` is inlined into the Vite bundle by `import.meta.env`, i.e. published. The ops
 * scripts reject `sb_` keys, but nothing stopped a hand-edited env file, so the client refuses too
 * — a build that cannot authenticate is a much better outcome than one that ships a secret.
 */
export function isBrowserSafeSupabaseKey(key: string): boolean {
  const trimmed = key.trim();
  if (!trimmed) return false;
  if (trimmed.toLowerCase().startsWith('sb_')) return false;
  return trimmed.startsWith('eyJ');
}

export function resolveSupabaseBackendEnabled(input: {
  isProd: boolean;
  isE2e: boolean;
  remoteBackend?: string;
  allowProduction?: string;
  allowStaging?: string;
  supabaseUrl: string;
  /** Omitted by callers that only test URL/flag rules; when present it must be browser-safe. */
  anonKey?: string;
  hostname?: string;
}): boolean {
  if (input.remoteBackend !== 'supabase') return false;
  if (input.anonKey != null && !isBrowserSafeSupabaseKey(input.anonKey)) return false;
  if (!input.isProd || input.isE2e) return true;
  if (isLocalSupabaseUrl(input.supabaseUrl)) return false;
  if (isTruthyFlag(input.allowProduction)) return true;
  return isTruthyFlag(input.allowStaging) && isPagesDevHost(input.hostname ?? '');
}
