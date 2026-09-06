import { useEffect } from 'react';
import type { AuthUser } from '@/lib/auth/authUser';
import { formatAuthError } from '@/lib/auth/authErrors';
import { clearLocalUserData } from '@/lib/bootstrap';
import { hadSessionLastLoad, forgetSignedIn, rememberSignedIn } from '@/lib/auth/sessionHint';
import { shouldStartSupabaseAuthOnBoot } from '@/lib/auth/supabaseAuthBoot';
import { useAuthStore } from '@/store/authStore';
import { useToastStore } from '@/store/toastStore';

function handleAuthUser(nextUser: AuthUser | null): void {
  if (nextUser) {
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

let authStart: Promise<void> | null = null;
let stopAuthListener: (() => void) | undefined;

/** Loads supabase-js and starts the session listener. Safe to call more than once. */
export function ensureSupabaseAuthStarted(): Promise<void> {
  authStart ??= (async () => {
    const { completeSupabaseOAuthRedirect, initSupabaseAuthListener } = await import(
      '@/lib/auth/supabaseAuth'
    );
    try {
      await completeSupabaseOAuthRedirect();
    } catch (error) {
      useToastStore.getState().show(formatAuthError(error), 'error');
    }
    stopAuthListener = initSupabaseAuthListener(handleAuthUser);
  })();
  return authStart;
}

/** Mount once in App — registers the only auth listener (Supabase) when a session is likely. */
export function useAuthSync() {
  useEffect(() => {
    if (!shouldStartSupabaseAuthOnBoot(hadSessionLastLoad(), window.location.search)) {
      useAuthStore.setState((state) => (state.isReady ? state : { ...state, isReady: true }));
      return;
    }
    let cancelled = false;
    void ensureSupabaseAuthStarted().then(() => {
      if (cancelled) {
        stopAuthListener?.();
        stopAuthListener = undefined;
        authStart = null;
      }
    });
    return () => {
      cancelled = true;
      stopAuthListener?.();
      stopAuthListener = undefined;
      authStart = null;
    };
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
