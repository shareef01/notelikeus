export function formatAuthError(error: unknown): string {
  if (isAuthLikeError(error)) {
    const message = error.message.toLowerCase();
    if (
      error.code === 'otp_expired' ||
      (message.includes('token') && (message.includes('expired') || message.includes('invalid')))
    ) {
      return 'That code is wrong or has expired. Check the latest email, or send a new code.';
    }
    if (
      error.code === 'over_email_send_rate_limit' ||
      (message.includes('rate limit') && message.includes('email')) ||
      message.includes('for security purposes')
    ) {
      return 'Too many codes requested. Wait a minute, then send a new code.';
    }
    if (error.code === 'over_request_rate_limit') {
      return 'Too many attempts. Wait a moment and try again.';
    }
    if (error.code === 'otp_disabled' || error.code === 'signup_disabled') {
      return 'Email sign-in is not available right now. Try again later.';
    }
    if (message.includes('network') || error.status === 0) {
      return 'Network error. Check your connection and try again.';
    }
    if (message.includes('already registered') || message.includes('already been registered')) {
      return 'That email already has an account. Use Sign in instead.';
    }
    if (message.includes('invalid email') || error.code === 'email_address_invalid') {
      return 'Enter a valid email address.';
    }
    if (message.includes('password') && message.includes('6')) {
      return 'Password must be at least 6 characters.';
    }
    if (
      message.includes('invalid login') ||
      message.includes('invalid credentials') ||
      error.code === 'invalid_credentials'
    ) {
      return 'Wrong email or password. Use Create if you do not have an account yet.';
    }
    if (message.includes('too many') || error.status === 429) {
      return 'Too many attempts. Wait a moment and try again.';
    }
    return error.message || 'Sign-in failed. Please try again.';
  }
  if (error instanceof Error) return error.message;
  return 'Sign-in failed. Please try again.';
}

/**
 * Supabase `AuthError` without importing `@supabase/supabase-js` on the auth-gate path.
 * The SDK's subclasses rename themselves (`AuthApiError`, `AuthRetryableFetchError`, ...), and
 * the server's own errors arrive as `AuthApiError`, so match the SDK's `__isAuthError` brand or
 * any `Auth…Error` name rather than `AuthError` alone.
 */
function isAuthLikeError(
  error: unknown,
): error is { message: string; status?: number; code?: string } {
  if (typeof error !== 'object' || error === null) return false;
  const candidate = error as { name?: unknown; message?: unknown };
  if (typeof candidate.message !== 'string') return false;
  return (
    Reflect.get(error, '__isAuthError') === true ||
    (typeof candidate.name === 'string' && /^Auth\w*Error$/.test(candidate.name))
  );
}
