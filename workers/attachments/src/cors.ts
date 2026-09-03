const ALLOWED_HEADERS = 'Authorization, Content-Type';
const ALLOWED_METHODS = 'GET, PUT, DELETE, OPTIONS';

export function isAllowedAttachmentOrigin(origin: string): boolean {
  if (!origin) return false;
  try {
    const host = new URL(origin).hostname.toLowerCase();
    return host === 'localhost' || host === '127.0.0.1' || host === '::1';
  } catch {
    return false;
  }
}

export function attachmentCorsHeaders(request: Request): Record<string, string> {
  const headers: Record<string, string> = {
    'Access-Control-Allow-Methods': ALLOWED_METHODS,
    'Access-Control-Allow-Headers': ALLOWED_HEADERS,
    Vary: 'Origin',
  };
  const origin = request.headers.get('Origin')?.trim() ?? '';
  if (isAllowedAttachmentOrigin(origin)) {
    headers['Access-Control-Allow-Origin'] = origin;
  }
  return headers;
}

export function withAttachmentCors(request: Request, response: Response): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(attachmentCorsHeaders(request))) {
    headers.set(key, value);
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
