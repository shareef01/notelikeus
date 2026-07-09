import { useEffect } from 'react';
import { onAuthStateChanged } from 'firebase/auth';
import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
import type { User } from 'firebase/auth';
import { useAuthStore } from '@/store/authStore';

/** Mount once in App — registers the only Firebase auth listener. */
export function useAuthSync() {
  useEffect(() => {
    try {
      initFirebase();
    } catch {
      useAuthStore.getState().setReady(true);
      return;
    }

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
