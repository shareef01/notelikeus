import { describe, expect, it } from 'vitest';
import { formatAuthError } from '@/lib/auth/authErrors';

function authError(message: string, status = 400, code?: string): Error {
  const error = new Error(message) as Error & { status?: number; code?: string };
  error.name = 'AuthError';
  error.status = status;
  if (code) error.code = code;
  return error;
}

describe('formatAuthError', () => {
  it('maps known auth messages to actionable copy', () => {
    const cases: Array<[Error, string]> = [
      [
        authError('Token has expired or is invalid', 403, 'otp_expired'),
        'That code is wrong or has expired. Check the latest email, or send a new code.',
      ],
      [
        authError('Token has expired or is invalid', 403),
        'That code is wrong or has expired. Check the latest email, or send a new code.',
      ],
      [
        authError('email rate limit exceeded', 429, 'over_email_send_rate_limit'),
        'Too many codes requested. Wait a minute, then send a new code.',
      ],
      [
        authError('For security purposes, you can only request this after 42 seconds.', 429),
        'Too many codes requested. Wait a minute, then send a new code.',
      ],
      [
        authError('Request rate limit reached', 429, 'over_request_rate_limit'),
        'Too many attempts. Wait a moment and try again.',
      ],
      [
        authError('Signups not allowed for otp', 422, 'otp_disabled'),
        'Email sign-in is not available right now. Try again later.',
      ],
      [authError('Network request failed', 0), 'Network error. Check your connection and try again.'],
      [authError('User already registered'), 'That email already has an account. Use Sign in instead.'],
      [authError('Invalid email', 400, 'email_address_invalid'), 'Enter a valid email address.'],
      [authError('Password should be at least 6 characters'), 'Password must be at least 6 characters.'],
      [authError('Too many requests', 429), 'Too many attempts. Wait a moment and try again.'],
    ];
    for (const [error, message] of cases) {
      expect(formatAuthError(error)).toBe(message);
    }
  });

  it('gives one shared message for invalid credentials, so it never reveals whether an account exists', () => {
    const messages = [
      authError('Invalid login credentials', 400, 'invalid_credentials'),
      authError('Invalid credentials'),
    ].map((error) => formatAuthError(error));
    expect(new Set(messages).size).toBe(1);
    expect(messages[0]).toBe(
      'Wrong email or password. Use Create if you do not have an account yet.',
    );
  });

  it('recognises the SDK subclasses the server actually returns', () => {
    // Supabase reports a wrong or expired code as an AuthApiError, not a plain AuthError.
    const apiError = Object.assign(new Error('Token has expired or is invalid'), {
      name: 'AuthApiError',
      status: 403,
      code: 'otp_expired',
    });
    expect(formatAuthError(apiError)).toBe(
      'That code is wrong or has expired. Check the latest email, or send a new code.',
    );
    const branded = Object.assign(new Error('email rate limit exceeded'), {
      name: 'SomethingElse',
      __isAuthError: true,
      status: 429,
    });
    expect(formatAuthError(branded)).toBe(
      'Too many codes requested. Wait a minute, then send a new code.',
    );
  });

  it('falls back to the raw message for unknown auth errors', () => {
    expect(formatAuthError(authError('boom'))).toContain('boom');
  });

  it('handles plain errors and non-error values', () => {
    expect(formatAuthError(new Error('offline'))).toBe('offline');
    expect(formatAuthError('nope')).toBe('Sign-in failed. Please try again.');
    expect(formatAuthError(undefined)).toBe('Sign-in failed. Please try again.');
  });
});
