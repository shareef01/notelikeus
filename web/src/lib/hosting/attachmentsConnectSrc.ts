/**
 * CSP `connect-src` origin for the attachments Worker.
 *
 * `https://*.workers.dev` is not pin-able in CSP (the account label sits in the middle),
 * so a wildcard would let an XSS exfil to any Worker on the platform. The build injects
 * exactly the origin from `VITE_ATTACHMENTS_WORKER_URL`, or omits it when attachments
 * are off.
 */
export function attachmentsConnectSrcOrigin(workerUrl: string): string | null {
  const trimmed = workerUrl.trim();
  if (!trimmed) return null;

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }

  if (parsed.hostname.includes('*')) return null;
  if (parsed.protocol === 'https:') return parsed.origin;
  if (parsed.protocol === 'http:' && isLoopbackHost(parsed.hostname)) return parsed.origin;
  return null;
}

export function applyAttachmentsConnectSrc(headers: string, origin: string | null): string {
  const withoutWildcard = headers.replace(/\shttps:\/\/\*\.workers\.dev\b/g, '');
  if (!origin) return withoutWildcard;
  if (withoutWildcard.includes(origin)) return withoutWildcard;

  const needle = 'wss://cqydlidescvmpfviwncf.supabase.co';
  if (!withoutWildcard.includes(needle)) {
    throw new Error('CSP connect-src is missing the pinned Supabase wss origin');
  }
  return withoutWildcard.replace(needle, `${needle} ${origin}`);
}

function isLoopbackHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return host === 'localhost' || host === '127.0.0.1' || host === '::1';
}
