export interface WorkerEnv {
  ATTACHMENTS_BUCKET: R2Bucket;
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  /** Comma-separated extra Origins (beyond localhost and *.pages.dev). */
  ALLOWED_ORIGINS?: string;
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
