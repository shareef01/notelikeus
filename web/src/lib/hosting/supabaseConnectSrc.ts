/**
 * CSP `connect-src` origins for the hosted (or local) Supabase project.
 *
 * Source `_headers` must not name a project host: a placeholder like
 * `[REDACTED].supabase.co` was deployed literally and blocked every real
 * `*.supabase.co` request. The build injects `VITE_SUPABASE_URL`.
 */
export function supabaseConnectSrcTokens(
  supabaseUrl: string,
): { https: string; wss: string } | null {
  const trimmed = supabaseUrl.trim();
  if (!trimmed) return null;

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }

  if (parsed.hostname.includes('*') || parsed.hostname.includes('[')) return null;

  if (parsed.protocol === 'https:') {
    return { https: parsed.origin, wss: `wss://${parsed.host}` };
  }
  if (parsed.protocol === 'http:' && isLoopbackHost(parsed.hostname)) {
    return { https: parsed.origin, wss: `ws://${parsed.host}` };
  }
  return null;
}

export function requireSupabaseConnectSrcTokens(supabaseUrl: string): {
  https: string;
  wss: string;
} {
  const tokens = supabaseConnectSrcTokens(supabaseUrl);
  if (!tokens) {
    throw new Error(
      `Cannot pin CSP: VITE_SUPABASE_URL is not a pin-able origin (${supabaseUrl || 'empty'})`,
    );
  }
  return tokens;
}

export function applySupabaseConnectSrc(
  headers: string,
  tokens: { https: string; wss: string },
): string {
  const stripped = headers
    .replace(/\shttps:\/\/\*\.supabase\.co\b/g, '')
    .replace(/\swss:\/\/\*\.supabase\.co\b/g, '')
    .replace(/\shttps:\/\/\[REDACTED\]\.supabase\.co\b/g, '')
    .replace(/\swss:\/\/\[REDACTED\]\.supabase\.co\b/g, '');

  if (stripped.includes(tokens.https) && stripped.includes(tokens.wss)) {
    return stripped;
  }

  const needle = "connect-src 'self'";
  if (!stripped.includes(needle)) {
    throw new Error("CSP connect-src is missing connect-src 'self'");
  }
  return stripped.replace(needle, `${needle} ${tokens.https} ${tokens.wss}`);
}

function isLoopbackHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return host === 'localhost' || host === '127.0.0.1' || host === '::1';
}
