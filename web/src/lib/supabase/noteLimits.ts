/**
 * The database's refusal of a note write that would take the account past its note limits (AUD-004):
 * 10 000 notes, or 100 MB of note text. SQLSTATE NL005, with a message that starts with
 * `account_note_limit:` and then says what to do.
 */
export const NOTE_LIMIT_SQLSTATE = 'NL005';
const NOTE_LIMIT_PREFIX = 'account_note_limit:';

export function isNoteLimitError(error: unknown): boolean {
  const { code, message } = (error ?? {}) as { code?: unknown; message?: unknown };
  if (code === NOTE_LIMIT_SQLSTATE) return true;
  return typeof message === 'string' && message.startsWith(NOTE_LIMIT_PREFIX);
}

/**
 * What to show the user: the database's own sentence without its machine-readable prefix, or a
 * general one if the message is missing. The notes stay on this device either way; only syncing
 * new ones is refused.
 */
export function noteLimitMessage(error: unknown): string {
  const { message } = (error ?? {}) as { message?: unknown };
  const text = typeof message === 'string' && message.startsWith(NOTE_LIMIT_PREFIX)
    ? message.slice(NOTE_LIMIT_PREFIX.length).trim()
    : '';
  if (!text) return 'This account has reached its note limit. Delete notes you no longer need to sync new ones.';
  return text.charAt(0).toUpperCase() + text.slice(1);
}
