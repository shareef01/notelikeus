export interface WorkerEnv {
  ATTACHMENTS_BUCKET: R2Bucket;
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  /** Comma-separated extra Origins (beyond localhost and *.pages.dev). */
  ALLOWED_ORIGINS?: string;
  /**
   * Firebase project whose ID tokens may prove uid ownership. Not a secret — it ships in every web
   * build — but load-bearing: without it, a validly signed token from any Firebase project would
   * verify. Absent means the verified-link route is disabled.
   */
  FIREBASE_PROJECT_ID?: string;
  /**
   * Supabase service-role key, used *only* to call `link_verified_firebase_uid` after a Firebase ID
   * token has been verified. That RPC is not reachable by `anon` or `authenticated`, which is what
   * stops a client asserting its own proof. Set with `wrangler secret put`, never in wrangler.toml.
   *
   * This key bypasses RLS, so it widens what a Worker compromise would reach. It is scoped as
   * tightly as the platform allows — one RPC, one purpose — and the route is simply disabled when
   * it is absent, degrading to unverified client claims rather than failing.
   */
  SUPABASE_SERVICE_ROLE_KEY?: string;
}

export async function resolveAuthenticatedUserId(
  request: Request,
  env: Pick<WorkerEnv, 'SUPABASE_URL' | 'SUPABASE_ANON_KEY'>,
): Promise<string | null> {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader?.startsWith('Bearer ')) return null;
  const token = authHeader.slice('Bearer '.length).trim();
  if (!token) return null;

  const response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, '')}/auth/v1/user`, {
    headers: {
      apikey: env.SUPABASE_ANON_KEY,
      Authorization: `Bearer ${token}`,
    },
  });
  if (!response.ok) return null;
  const payload = (await response.json()) as { id?: string };
  return payload.id?.trim() || null;
}
