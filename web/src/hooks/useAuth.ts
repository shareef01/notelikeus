import { useEffect } from 'react';
import { onAuthStateChanged } from 'firebase/auth';
import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
import type { User } from 'firebase/auth';
import { useAuthStore } from '@/store/authStore';
import { completeGoogleRedirectSignIn } from '@/lib/auth/googleAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import { useToastStore } from '@/store/toastStore';

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
} {
  const user = useAuthStore((state) => state.user);
  const isReady = useAuthStore((state) => state.isReady);

  return {
    user,
    userId: user?.uid ?? null,
    isReady,
  };
}
