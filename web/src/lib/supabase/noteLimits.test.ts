import { describe, expect, it } from 'vitest';
import { isNoteLimitError, noteLimitMessage } from '@/lib/supabase/noteLimits';

/** AUD-004: the account's note limits are reported as something to act on, not a raw refusal. */
describe('note limits', () => {
  const countLimit = {
    code: 'NL005',
    message:
      'account_note_limit: this account has reached its limit of 10000 notes. Delete notes you no longer need to sync new ones.',
  };

  it('recognises the refusal by its SQLSTATE or its message prefix', () => {
    expect(isNoteLimitError(countLimit)).toBe(true);
    expect(isNoteLimitError({ message: 'account_note_limit: full' })).toBe(true);
  });

  it.each([
    [null],
    [new Error('Failed to fetch')],
    [{ code: '22023', message: 'note title exceeds 2000 characters' }],
    [{ code: 'NL003', message: 'account_deleted: this account was deleted.' }],
    [{ message: 'a note that mentions account_note_limit: in passing' }],
  ])('does not read %o as the note limit', (error) => {
    expect(isNoteLimitError(error)).toBe(false);
  });

  it('shows the database sentence without its prefix', () => {
    expect(noteLimitMessage(countLimit)).toBe(
      'This account has reached its limit of 10000 notes. Delete notes you no longer need to sync new ones.',
    );
  });

  it('falls back to a general sentence', () => {
    expect(noteLimitMessage({ code: 'NL005' })).toContain('reached its note limit');
  });
});
