export interface WorkerEnv {
  ATTACHMENTS_BUCKET: R2Bucket;
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  /**
   * Worker cron only. Never ship this in a client app. User PUT/GET/DELETE
   * still use the caller's bearer + anon key.
   */
  SUPABASE_SERVICE_ROLE_KEY?: string;
  /** Comma-separated extra Origins (beyond localhost and notelikeus[.pages.dev] projects). */
  ALLOWED_ORIGINS?: string;
  /**
   * Optional Cloudflare rate-limiting binding. Cloudflare counts these at the edge rather than
   * in isolate memory, so the limit holds across every isolate the Worker runs in. Absent
   * binding means no Worker-level throttling at all ÔÇö see `wrangler.toml.example`.
   */
  ATTACHMENT_RATE_LIMITER?: RateLimiterBinding;
}

export interface RateLimiterBinding {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export class UpstreamServiceError extends Error {
  constructor(
    message: string,
    public readonly status: number = 502,
  ) {
    super(message);
    this.name = 'UpstreamServiceError';
  }
}

function parseAndValidateUserId(rawBody: string): string {
  let payload: unknown;
  try {
    payload = JSON.parse(rawBody);
  } catch {
    throw new UpstreamServiceError('Upstream auth service returned malformed JSON', 502);
  }

  if (
    typeof payload !== 'object' ||
    payload === null ||
    Array.isArray(payload)
  ) {
    throw new UpstreamServiceError('Upstream auth service returned malformed response shape', 502);
  }

  const record = payload as Record<string, unknown>;
  if (typeof record.id !== 'string') {
    throw new UpstreamServiceError('Upstream auth service returned missing or non-string user id', 502);
  }

  const trimmed = record.id.trim();
  if (trimmed.length === 0) {
    throw new UpstreamServiceError('Upstream auth service returned blank user id', 502);
  }

  return trimmed;
}

export async function resolveAuthenticatedUserId(
  request: Request,
  env: Pick<WorkerEnv, 'SUPABASE_URL' | 'SUPABASE_ANON_KEY'>,
): Promise<string | null> {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader?.startsWith('Bearer ')) return null;
  const token = authHeader.slice('Bearer '.length).trim();
  if (!token) return null;

  let response: Response;
  try {
    response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, '')}/auth/v1/user`, {
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: `Bearer ${token}`,
      },
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new UpstreamServiceError('Upstream auth service unreachable', 503);
  }

  if (response.status === 401 || response.status === 403) {
    return null;
  }
  if (response.status >= 500) {
    throw new UpstreamServiceError('Upstream auth service error', 502);
  }
  if (!response.ok) return null;

  const rawBody = await response.text();
  return parseAndValidateUserId(rawBody);
}
