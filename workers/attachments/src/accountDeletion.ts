import { rpc, serviceRoleHeaders, type SweepResult } from './sweep';
import { UpstreamServiceError, type WorkerEnv } from './auth';

const OWNER_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * How recently the session must have signed in to delete the account (AUD-005). The same window as
 * `notelikeus_internal.assert_signed_in_recently` in 20261104000000, which checks it again.
 */
export const RECENT_SIGN_IN_MS = 10 * 60 * 1000;
/** Clock skew allowed into the future, as in the database. */
const SIGN_IN_SKEW_MS = 5 * 60 * 1000;

/** The answer a client turns into "confirm it's you": it asks for a new code and retries. */
export const REAUTHENTICATION_REQUIRED = 'reauthentication_required';

export function reauthenticationRequired(): Response {
  return Response.json({ error: REAUTHENTICATION_REQUIRED }, { status: 403 });
}

/**
 * When the session behind [authorization] last signed in, in milliseconds, or null unless that was
 * within {@link RECENT_SIGN_IN_MS}.
 *
 * Read from the access token's `amr` claim, where GoTrue records each sign-in method with the moment
 * it was used and carries those moments over a refresh unchanged. Only called after Supabase Auth has
 * accepted this token for [userId], so the payload is GoTrue's own; its subject is still compared,
 * and anything not shaped as expected counts as no recent sign-in.
 */
export function recentSignInAt(
  authorization: string | null,
  userId: string,
  now: number = Date.now(),
): number | null {
  const token = authorization?.startsWith('Bearer ') ? authorization.slice('Bearer '.length).trim() : '';
  const payload = token.split('.')[1];
  if (!payload) return null;
  let claims: { sub?: unknown; amr?: unknown };
  try {
    claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(payload))) as typeof claims;
  } catch {
    return null;
  }
  if (claims.sub !== userId || !Array.isArray(claims.amr)) return null;
  let latest: number | null = null;
  for (const entry of claims.amr) {
    const seconds = (entry as { timestamp?: unknown } | null)?.timestamp;
    if (typeof seconds !== 'number' || !Number.isFinite(seconds)) continue;
    latest = Math.max(latest ?? 0, seconds * 1000);
  }
  if (latest === null || latest < now - RECENT_SIGN_IN_MS || latest > now + SIGN_IN_SKEW_MS) return null;
  return latest;
}

function base64UrlDecode(value: string): Uint8Array {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
  return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
}

/**
 * Step 1 of the protocol, through the service role for the owner this Worker verified (AUD-006).
 *
 * Returns {@link REAUTHENTICATION_REQUIRED} when the database judges the sign-in stale itself (NL004),
 * which a clock difference between the Worker and the database can cause. Throws on anything else
 * that is not an acceptance, and then nothing was deleted.
 */
export async function requestAccountDeletion(
  env: WorkerEnv,
  ownerId: string,
  signedInAt: number,
): Promise<'accepted' | typeof REAUTHENTICATION_REQUIRED> {
  const headers = serviceRoleHeaders(env);
  if (!headers) throw new UpstreamServiceError('Account deletion needs a service-role key', 503);
  let response: Response;
  try {
    response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, '')}/rest/v1/rpc/request_account_deletion_for`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ p_owner_id: ownerId, p_signed_in_at: new Date(signedInAt).toISOString() }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new UpstreamServiceError('Upstream database unreachable', 503);
  }
  const body = await response.text().catch(() => '');
  if (!response.ok) {
    if (body.includes('NL004')) return REAUTHENTICATION_REQUIRED;
    // PGRST202: a database without 20261104000000. Nothing was deleted, and the deployment is at fault.
    throw new UpstreamServiceError('Account deletion request failed', response.status === 404 ? 503 : 502);
  }
  let parsed: { status?: unknown };
  try {
    parsed = JSON.parse(body) as typeof parsed;
  } catch {
    throw new UpstreamServiceError('Upstream database response malformed', 502);
  }
  if (parsed?.status !== 'accepted') throw new UpstreamServiceError('Account deletion was not accepted', 502);
  return 'accepted';
}

/** One run's budget per account. An account holding more finishes on later cron runs. */
const PURGE_MAX_PAGES = 5;
const PURGE_PAGE_SIZE = 1000;

/** Accounts per cron run: at the page budget above, one run stays inside the subrequest limit. */
const PENDING_LIMIT = 25;

/** Whether this deployment can delete auth users at all. Checked before anything is destroyed. */
export function accountDeletionAvailable(env: WorkerEnv): boolean {
  return serviceRoleHeaders(env) !== null;
}

/**
 * Deletes every object under this owner's prefix, and says whether the prefix is now empty.
 *
 * The prefix rather than the attachment rows, because it also reaches bytes no row references. The
 * id is checked first and the prefix ends in a slash: this is the one delete in the Worker that is not
 * derived from a single object key, and a blank id here would be the whole bucket.
 */
async function purgeOwnerObjects(env: WorkerEnv, ownerId: string): Promise<boolean> {
  if (!OWNER_ID_PATTERN.test(ownerId)) throw new Error('invalid owner id');
  const prefix = `owners/${ownerId}/`;
  for (let page = 0; page < PURGE_MAX_PAGES; page += 1) {
    // Always the first page: the previous page's objects are gone, so a cursor would skip nothing.
    const listing = await env.ATTACHMENTS_BUCKET.list({ prefix, limit: PURGE_PAGE_SIZE });
    if (listing.objects.length === 0) return true;
    await env.ATTACHMENTS_BUCKET.delete(listing.objects.map((object) => object.key));
  }
  const remaining = await env.ATTACHMENTS_BUCKET.list({ prefix, limit: 1 });
  return remaining.objects.length === 0;
}

/** Deletes the auth user. A 404 is a success: a retry of a delete that already landed. */
async function deleteAuthUser(env: WorkerEnv, ownerId: string): Promise<void> {
  const headers = serviceRoleHeaders(env);
  if (!headers) throw new Error('Account deletion needs a service-role key');
  const response = await fetch(
    `${env.SUPABASE_URL.replace(/\/$/, '')}/auth/v1/admin/users/${encodeURIComponent(ownerId)}`,
    { method: 'DELETE', headers, signal: AbortSignal.timeout(10_000) },
  );
  if (!response.ok && response.status !== 404) {
    throw new Error(`Auth admin delete failed (${response.status})`);
  }
}

/**
 * Steps 2 and 3 of the protocol in `20261102010000_account_deletion.sql`, for a request already
 * recorded. True when the auth user is gone. False or a throw leaves the request for the cron sweep,
 * and the account's notes are already gone either way.
 *
 * The order is what keeps this retryable: the request row, not the auth user, is what the sweep works
 * from, and the user goes last so nothing is left that only they could have asked to remove.
 */
export async function finishAccountDeletion(env: WorkerEnv, ownerId: string): Promise<boolean> {
  if (!(await purgeOwnerObjects(env, ownerId))) return false;
  await deleteAuthUser(env, ownerId);
  await rpc(env, 'mark_account_auth_user_deleted', { p_owner_id: ownerId });
  return true;
}

interface PendingDeletion {
  owner_id?: string;
  auth_user_deleted?: boolean;
  settled?: boolean;
}

/**
 * Finishes account deletions a request left behind, and forgets the settled ones.
 *
 * A request stays listed after its user is gone because an upload already in flight when the account
 * was deleted can still land bytes. So each run lists the prefix again, and a request is only
 * forgotten once it is settled and the prefix is empty.
 */
export async function sweepPendingAccountDeletions(env: WorkerEnv): Promise<SweepResult> {
  if (!serviceRoleHeaders(env)) return { scanned: 0, deleted: 0, skipped: 0 };

  const rows = await rpc<PendingDeletion[]>(env, 'list_pending_account_deletions', {
    p_limit: PENDING_LIMIT,
  });
  if (!Array.isArray(rows)) return { scanned: 0, deleted: 0, skipped: 0 };

  let deleted = 0;
  let skipped = 0;
  for (const row of rows) {
    const ownerId = row.owner_id?.trim() ?? '';
    try {
      if (!row.auth_user_deleted) {
        if (await finishAccountDeletion(env, ownerId)) deleted += 1;
        else skipped += 1;
        continue;
      }
      const empty = await purgeOwnerObjects(env, ownerId);
      if (empty && row.settled) {
        await rpc(env, 'complete_account_deletion', { p_owner_id: ownerId });
        deleted += 1;
      } else {
        skipped += 1;
      }
    } catch {
      // One account's failure must not stop the others. The row stays, so the next run retries it.
      console.error('[Sweep] Account deletion left pending');
      skipped += 1;
    }
  }
  return { scanned: rows.length, deleted, skipped };
}
