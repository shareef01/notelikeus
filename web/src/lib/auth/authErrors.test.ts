import { FirebaseError } from 'firebase/app';
import { describe, expect, it } from 'vitest';
import { formatAuthError } from '@/lib/auth/authErrors';

describe('formatAuthError', () => {
  it('maps known auth codes to actionable copy', () => {
    const cases: Array<[string, string]> = [
      ['auth/popup-closed-by-user', 'Sign-in was cancelled.'],
      ['auth/popup-blocked', 'Pop-up blocked. Allow pop-ups for this site and try again.'],
      ['auth/cancelled-popup-request', 'Sign-in was interrupted. Please try again.'],
      ['auth/network-request-failed', 'Network error. Check your connection and try again.'],
      ['auth/email-already-in-use', 'That email already has an account. Use Sign in instead.'],
      ['auth/invalid-email', 'Enter a valid email address.'],
      ['auth/weak-password', 'Password must be at least 6 characters.'],
      ['auth/too-many-requests', 'Too many attempts. Wait a moment and try again.'],
    ];
    for (const [code, message] of cases) {
      expect(formatAuthError(new FirebaseError(code, 'raw'))).toBe(message);
    }
  });

  it('gives one shared message for the credential codes, so it never reveals whether an account exists', () => {
    const messages = [
      'auth/invalid-credential',
      'auth/wrong-password',
      'auth/user-not-found',
    ].map((code) => formatAuthError(new FirebaseError(code, 'raw')));
    expect(new Set(messages).size).toBe(1);
    expect(messages[0]).toBe(
      'Wrong email or password. Use Create if you do not have an account yet.',
    );
  });

  it('falls back to the raw message for unknown auth codes', () => {
    expect(formatAuthError(new FirebaseError('auth/whatever', 'boom'))).toContain('boom');
  });

  it('handles plain errors and non-error values', () => {
    expect(formatAuthError(new Error('offline'))).toBe('offline');
    expect(formatAuthError('nope')).toBe('Sign-in failed. Please try again.');
    expect(formatAuthError(undefined)).toBe('Sign-in failed. Please try again.');
  });
});
