import { describe, expect, it } from 'vitest';
import { formatUnknownError, toError } from '@/lib/errors/formatUnknownError';

describe('formatUnknownError', () => {
  it('returns Error.message when present', () => {
    expect(formatUnknownError(new Error('disk full'))).toBe('disk full');
  });

  it('returns plain strings', () => {
    expect(formatUnknownError('  offline  ')).toBe('offline');
  });

  it('never surfaces "[object Object]" from String(object)', () => {
    expect(formatUnknownError({ message: undefined, code: 'x' })).not.toBe('[object Object]');
    expect(formatUnknownError({})).not.toBe('[object Object]');
    expect(formatUnknownError(new Error(String({ a: 1 })))).not.toBe('[object Object]');
  });

  it('reads Supabase-style error objects', () => {
    expect(
      formatUnknownError({
        message: 'JWT expired',
        code: 'PGRST301',
        details: null,
        hint: null,
      }),
    ).toBe('JWT expired');

    expect(
      formatUnknownError({
        error: 'invalid_grant',
        error_description: 'Invalid Refresh Token',
      }),
    ).toBe('Invalid Refresh Token');
  });

  it('falls back for empty or useless messages', () => {
    expect(formatUnknownError(new Error(''))).toBe('Something went wrong. Please try again.');
    expect(formatUnknownError(new Error('[object Object]'))).toBe(
      'Something went wrong. Please try again.',
    );
    expect(formatUnknownError(null)).toBe('Something went wrong. Please try again.');
    expect(formatUnknownError(undefined, 'Could not load notes')).toBe('Could not load notes');
  });

  it('uses DOMException-like name/code when message is missing', () => {
    expect(formatUnknownError({ name: 'QuotaExceededError', code: 22 })).toBe(
      'QuotaExceededError (22)',
    );
  });

  it('walks cause chains', () => {
    expect(formatUnknownError({ cause: { message: 'upstream timeout' } })).toBe(
      'upstream timeout',
    );
  });
});

describe('toError', () => {
  it('preserves a useful Error instance', () => {
    const original = new Error('keep me');
    expect(toError(original)).toBe(original);
  });

  it('wraps objects without producing "[object Object]"', () => {
    const wrapped = toError({ message: 'row level security' });
    expect(wrapped).toBeInstanceOf(Error);
    expect(wrapped.message).toBe('row level security');
    expect(wrapped.message).not.toBe('[object Object]');
  });
});
