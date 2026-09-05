import { AuthError } from '@supabase/supabase-js';

export function formatAuthError(error: unknown): string {
  if (error instanceof AuthError) {
    const message = error.message.toLowerCase();
    if (message.includes('popup') && message.includes('closed')) {
      return 'Sign-in was cancelled.';
    }
    if (message.includes('popup') && message.includes('blocked')) {
      return 'Pop-up blocked. Allow pop-ups for this site and try again.';
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
