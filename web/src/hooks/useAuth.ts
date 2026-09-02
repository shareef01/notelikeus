import { useEffect } from 'react';
import { initFirebase } from '@/lib/firebase';
import type { AuthUser } from '@/lib/auth/authUser';
import { initFirebaseAuthListener } from '@/lib/auth/firebaseAuthListener';
import { completeGoogleRedirectSignIn } from '@/lib/auth/googleAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import { clearLocalUserData } from '@/lib/bootstrap';
import {
  completeSupabaseOAuthRedirect,
  initSupabaseAuthListener,
} from '@/lib/auth/supabaseAuth';
import { useAuthStore } from '@/store/authStore';
import { forgetSignedIn, rememberSignedIn } from '@/lib/auth/sessionHint';
import { rememberKnownFirebaseUid } from '@/lib/migration/firebaseSupabaseMigration';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';
import { useToastStore } from '@/store/toastStore';

function handleAuthUser(nextUser: AuthUser | null): void {
  if (nextUser) {
    if (!isSupabaseBackendEnabled()) {
      rememberKnownFirebaseUid(nextUser.uid);
    }
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
}

/** Mount once in App — registers the only auth listener (Firebase or Supabase). */
export function useAuthSync() {
  useEffect(() => {
    if (isSupabaseBackendEnabled()) {
      void completeSupabaseOAuthRedirect().catch((error) => {
        useToastStore.getState().show(formatAuthError(error), 'error');
      });
      const unsubscribe = initSupabaseAuthListener(handleAuthUser);
      return unsubscribe;
    }

    try {
      initFirebase();
    } catch (error) {
      console.error('[Notelikeus] Firebase init failed; auth is unavailable:', error);
      useAuthStore.getState().setReady(true);
      return;
    }

    void completeGoogleRedirectSignIn().catch((error) => {
      useToastStore.getState().show(formatAuthError(error), 'error');
    });

    return initFirebaseAuthListener(handleAuthUser);
  }, []);
}

/** Read auth state. Does not register listeners. */
export function useAuthListener(): {
  user: AuthUser | null;
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
