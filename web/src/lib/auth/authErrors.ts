import { FirebaseError } from 'firebase/app';

export function formatAuthError(error: unknown): string {
  if (error instanceof FirebaseError) {
    switch (error.code) {
      case 'auth/popup-closed-by-user':
        return 'Sign-in was cancelled.';
      case 'auth/popup-blocked':
        return 'Pop-up blocked. Allow pop-ups for this site and try again.';
      case 'auth/cancelled-popup-request':
        return 'Sign-in was interrupted. Please try again.';
      case 'auth/network-request-failed':
        return 'Network error. Check your connection and try again.';
      case 'auth/unauthorized-domain':
        return 'This domain is not authorized in Firebase. Add it under Authentication → Settings.';
      default:
        return error.message;
    }
  }
  if (error instanceof Error) return error.message;
  return 'Sign-in failed. Please try again.';
}
