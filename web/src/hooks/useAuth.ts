import { useEffect } from 'react';
import { onAuthStateChanged } from 'firebase/auth';
import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
import type { User } from 'firebase/auth';
import { useAuthStore } from '@/store/authStore';
import { completeGoogleRedirectSignIn } from '@/lib/auth/googleAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import { clearLocalUserData } from '@/lib/bootstrap';
import { useToastStore } from '@/store/toastStore';
import { forgetSignedIn, rememberSignedIn } from '@/lib/auth/sessionHint';

/** Mount once in App — registers the only Firebase auth listener. */
export function useAuthSync() {
  useEffect(() => {
    try {
      initFirebase();
    } catch {
      useAuthStore.getState().setReady(true);
      return;
    }

    // Surfaces errors from a signInWithRedirect fallback (see googleAuth.ts) that
    // completes after the page reloads — onAuthStateChanged below still picks up
    // a successful result on its own, this only catches a failed redirect attempt.
    void completeGoogleRedirectSignIn().catch((error) => {
      useToastStore.getState().show(formatAuthError(error), 'error');
    });

    const auth = getFirebaseAuth();
    return onAuthStateChanged(auth, (nextUser) => {
      if (nextUser) {
        // Leaving guest mode: guest notes and tombstones are in-session throwaways, so they must
        // not leak into the account — a guest tombstone could suppress a real cloud note (or the
        // in-memory guest mirror could get mixed into the account's first merge).
        if (useAuthStore.getState().guestMode) {
          clearLocalUserData();
          useAuthStore.getState().exitGuestMode();
        }
        rememberSignedIn();
      } else {
        forgetSignedIn();
      }
      useAuthStore.setState((state) => {
        if (state.user?.uid === nextUser?.uid && state.isReady) {
          return state;
        }
        return { user: nextUser, isReady: true };
      });
    });
  }, []);
}

/** Read auth state. Does not register listeners. */
export function useAuthListener(): {
  user: User | null;
  userId: string | null;
  isReady: boolean;
  isGuest: boolean;
} {
  const user = useAuthStore((state) => state.user);
  const isReady = useAuthStore((state) => state.isReady);
  const isGuest = useAuthStore((state) => state.guestMode);

  return {
    user,
    userId: user?.uid ?? null,
    isReady,
    isGuest,
  };
}
