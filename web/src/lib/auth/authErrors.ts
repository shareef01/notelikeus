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
      case 'auth/email-already-in-use':
        return 'That email already has an account. Use Sign in instead.';
      case 'auth/invalid-email':
        return 'Enter a valid email address.';
      case 'auth/weak-password':
        return 'Password must be at least 6 characters.';
      case 'auth/invalid-credential':
      case 'auth/wrong-password':
      case 'auth/user-not-found':
        return 'Wrong email or password. Use Create if you do not have an account yet.';
      case 'auth/too-many-requests':
        return 'Too many attempts. Wait a moment and try again.';
      case 'auth/operation-not-allowed':
        return 'Email/Password sign-in is disabled in Firebase Console → Authentication.';
      case 'auth/admin-restricted-operation':
        return 'This sign-in method is restricted in Firebase. Check Authentication settings.';
      default:
        return error.message || 'Sign-in failed. Please try again.';
    }
  }
  if (error instanceof Error) return error.message;
  return 'Sign-in failed. Please try again.';
}
