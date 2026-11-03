import { loadAttachmentsWorkerUrl } from '@/lib/attachments/attachmentConfig';
import { ReauthenticationRequiredError } from '@/lib/auth/reauthentication';
import { getSupabaseClient } from '@/lib/supabase/client';
import { ensureSupabaseAuthenticated } from '@/lib/supabase/supabaseSyncEngine';

/**
 * The SQLSTATE the database raises for an account being deleted. See
 * `supabase/migrations/20261102010000_account_deletion.sql`.
 */
export const ACCOUNT_DELETED = 'NL003';

/**
 * Whether the server definitely said this account was deleted.
 *
 * Erasing the local copy hangs on this, so it reads only that answer and nothing that could be a
 * network failure or an expired session. The message prefix is checked too, so a proxy that rewrites
 * the body does not turn a definite answer into a sync error that repeats forever.
 */
export function isAccountDeletedError(error: unknown): boolean {
  const { code, message } = (error ?? {}) as { code?: unknown; message?: unknown };
  if (code === ACCOUNT_DELETED) return true;
  return typeof message === 'string' && message.includes('account_deleted:');
}

/**
 * Asks the Worker to delete the signed-in account. Resolves once the server has accepted it: 200 when
 * it is finished, 202 when its hourly sweep will finish it, and the notes are gone in both cases.
 * Throws otherwise, and then nothing has been deleted: [ReauthenticationRequiredError] when this
 * session has to confirm it is the user first.
 */
export async function requestAccountDeletion(): Promise<void> {
  const workerUrl = loadAttachmentsWorkerUrl().replace(/\/$/, '');
  if (!workerUrl) throw new Error('Account deletion is not available in this build.');

  await ensureSupabaseAuthenticated();
  const { data } = await getSupabaseClient().auth.getSession();
  const token = data.session?.access_token?.trim();
  if (!token) throw new Error('Sign in again to delete your account.');

  let response: Response;
  try {
    response = await fetch(`${workerUrl}/v1/account`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch {
    throw new Error('Could not reach the server. Your account was not deleted.');
  }
  if (response.status === 401) throw new Error('Sign in again to delete your account.');
  // AUD-005: the session is too old to delete the account. Nothing was deleted; the caller asks for
  // a new code and retries.
  if (response.status === 403) {
    const body = await response.text().catch(() => '');
    if (body.includes('reauthentication_required')) throw new ReauthenticationRequiredError();
  }
  if (!response.ok) throw new Error('Could not delete your account. Please try again.');
}
