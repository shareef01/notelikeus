const ALLOWED_HEADERS = 'Authorization, Content-Type';
export const ATTACHMENT_ALLOWED_METHODS = 'GET, PUT, DELETE, OPTIONS';
const ALLOWED_METHODS = ATTACHMENT_ALLOWED_METHODS;

function extraOriginsFromEnv(allowedOrigins: string | undefined): string[] {
  return (allowedOrigins ?? '')
    .split(',')
    .map((origin) => origin.trim())
    .filter((origin) => origin.length > 0);
}

/** Production + staging Pages projects (preview subdomains included). */
const PAGES_PROJECT_HOSTS = ['notelikeus.pages.dev', 'notelikeus-dev.pages.dev'] as const;

function isPagesProjectHost(host: string): boolean {
  return PAGES_PROJECT_HOSTS.some(
    (projectHost) => host === projectHost || host.endsWith(`.${projectHost}`),
  );
}

export function isAllowedAttachmentOrigin(
  origin: string,
  allowedOrigins?: string,
): boolean {
  if (!origin) return false;
  try {
    const url = new URL(origin);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
    const host = url.hostname.toLowerCase();
    if (host === 'localhost' || host === '127.0.0.1' || host === '::1') return true;
    if (url.protocol === 'https:' && isPagesProjectHost(host)) {
      return true;
    }
    return extraOriginsFromEnv(allowedOrigins).includes(origin);
  } catch {
    return false;
  }
}

export function attachmentCorsHeaders(
  request: Request,
  allowedOrigins?: string,
): Record<string, string> {
  const headers: Record<string, string> = {
    'Access-Control-Allow-Methods': ALLOWED_METHODS,
    'Access-Control-Allow-Headers': ALLOWED_HEADERS,
    Vary: 'Origin',
  };
  const origin = request.headers.get('Origin')?.trim() ?? '';
  if (isAllowedAttachmentOrigin(origin, allowedOrigins)) {
    headers['Access-Control-Allow-Origin'] = origin;
  }
  return headers;
}

export function withAttachmentCors(
  request: Request,
  response: Response,
  allowedOrigins?: string,
): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(attachmentCorsHeaders(request, allowedOrigins))) {
    headers.set(key, value);
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
