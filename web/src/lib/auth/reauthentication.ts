/**
 * The server's answer that a destructive action needs a recent sign-in (AUD-005).
 *
 * Deleting the account or wiping its cloud data requires that this session signed in within the last
 * ten minutes. The database answers NL004 when it did not, and the Worker answers 403
 * `reauthentication_required`. Neither means anything was deleted: the caller asks the user for a new
 * emailed code, which starts a fresh session for the same account, and retries.
 */
export class ReauthenticationRequiredError extends Error {
  constructor() {
    super('For your security, confirm it’s you with a new sign-in code, then try again.');
    this.name = 'ReauthenticationRequiredError';
  }
}

export const REAUTHENTICATION_REQUIRED_SQLSTATE = 'NL004';

/**
 * Whether [error] is that answer. Reads the SQLSTATE, the error type, or the database's message
 * prefix; never a status code alone, which a proxy or an outage could also produce.
 */
export function isReauthenticationRequired(error: unknown): boolean {
  if (error instanceof ReauthenticationRequiredError) return true;
  const { code, message } = (error ?? {}) as { code?: unknown; message?: unknown };
  if (code === REAUTHENTICATION_REQUIRED_SQLSTATE) return true;
  return typeof message === 'string' && message.startsWith('reauthentication_required:');
}
