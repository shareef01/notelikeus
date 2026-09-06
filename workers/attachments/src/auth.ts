export interface WorkerEnv {
  ATTACHMENTS_BUCKET: R2Bucket;
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  /**
   * Worker cron only. Never ship this in a client app. User PUT/GET/DELETE
   * still use the caller's bearer + anon key.
   */
  SUPABASE_SERVICE_ROLE_KEY?: string;
  /** Comma-separated extra Origins (beyond localhost and notelikeus-dev.pages.dev). */
  ALLOWED_ORIGINS?: string;
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

  const payload = (await response.json()) as { id?: string };
  return payload.id?.trim() || null;
}
