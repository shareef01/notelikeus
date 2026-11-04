import { loadAttachmentsWorkerUrl } from '@/lib/attachments/attachmentConfig';
import { ReauthenticationRequiredError } from '@/lib/auth/reauthentication';
import { getSupabaseClient } from '@/lib/supabase/client';
import { ensureSupabaseAuthenticated } from '@/lib/supabase/supabaseSyncEngine';

/**
 * The SQLSTATE the database raises for an account being deleted. See
 * `supabase/migrations/20261102010000_account_deletion.sql`.
 */
export const ACCOUNT_DELETED = 'NL003';

/** How the database's own message for that answer begins. */
const ACCOUNT_DELETED_PREFIX = 'account_deleted:';

/**
 * Whether the server definitely said this account was deleted.
 *
 * Erasing the local copy hangs on this, so it reads only that answer and nothing that could be a
 * network failure or an expired session. The message prefix is checked too, so a proxy that rewrites
 * the code does not turn a definite answer into a sync error that repeats forever, but only as a
 * prefix (AUD-009): a message that merely contains it, such as one quoting a note's text, does not
 * count, because text the user typed must never be able to erase their local copy.
 */
export function isAccountDeletedError(error: unknown): boolean {
  const { code, message } = (error ?? {}) as { code?: unknown; message?: unknown };
  if (code === ACCOUNT_DELETED) return true;
  return typeof message === 'string' && message.startsWith(ACCOUNT_DELETED_PREFIX);
}

/**
 * Account deletion failed in a way that does not say whether the server accepted it, and asking the
 * server did not settle it either (AUD-010). If the account was deleted, this browser learns so at its
 * next sync and removes its copy then.
 */
export class AccountDeletionOutcomeUnknownError extends Error {
  constructor() {
    super(
      'Couldn’t confirm whether your account was deleted. Check your connection. If it was, ' +
        'this browser removes its copy the next time it syncs.',
    );
    this.name = 'AccountDeletionOutcomeUnknownError';
  }
}

/**
 * After a failure that may have come after the database accepted the request: true when the account
 * turns out to be deleted, false when it is still live, and a throw when that cannot be told.
 * `get_sync_generation` raises NL003 for an account being deleted and answers normally otherwise.
 */
async function accountWasDeleted(): Promise<boolean> {
  let error: unknown;
  try {
    ({ error } = await getSupabaseClient().rpc('get_sync_generation'));
  } catch (thrown) {
    error = thrown;
  }
  if (!error) return false;
  if (isAccountDeletedError(error)) return true;
  throw new AccountDeletionOutcomeUnknownError();
}

/**
 * Asks the Worker to delete the signed-in account. Resolves once the server has accepted it: 200 when
 * it is finished, 202 when its hourly sweep will finish it, and the notes are gone in both cases.
 * Throws otherwise, and then nothing has been deleted: [ReauthenticationRequiredError] when this
 * session has to confirm it is the user first. A failure that may have come after the request was
 * accepted is settled by asking the server; [AccountDeletionOutcomeUnknownError] when it cannot be.
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
    // The request may have reached the Worker before the connection dropped.
    if (await accountWasDeleted()) return;
    throw new Error('Could not reach the server. Your account was not deleted.');
  }
  if (response.status === 401) throw new Error('Sign in again to delete your account.');
  // AUD-005: the session is too old to delete the account. Nothing was deleted; the caller asks for
  // a new code and retries.
  if (response.status === 403) {
    const body = await response.text().catch(() => '');
    if (body.includes('reauthentication_required')) throw new ReauthenticationRequiredError();
  }
  if (response.ok) return;
  // A 4xx is the Worker refusing before anything was deleted. A 5xx can follow an accepted request,
  // for instance when the database's answer timed out on the way back (AUD-010).
  if (response.status >= 500 && (await accountWasDeleted())) return;
  throw new Error('Could not delete your account. Nothing was deleted. Please try again.');
}
