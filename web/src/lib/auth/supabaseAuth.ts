import type { AuthChangeEvent, Session } from '@supabase/supabase-js';
import { clearLocalUserData } from '@/lib/bootstrap';
import type { AuthUser } from '@/lib/auth/authUser';
import { authUserFromSupabase } from '@/lib/auth/authUser';
import { stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { getSupabaseClient } from '@/lib/supabase/client';

export function initSupabaseAuthListener(
  onUser: (user: AuthUser | null) => void,
): () => void {
  const client = getSupabaseClient();

  const applySession = (session: Session | null) => {
    onUser(session?.user ? authUserFromSupabase(session.user) : null);
  };

  void client.auth
    .getSession()
    .then(({ data, error }) => {
      if (error) {
        console.warn('[Notelikeus] Supabase session restore failed:', error.message);
        applySession(null);
        return;
      }
      applySession(data.session);
    })
    .catch((error: unknown) => {
      console.warn('[Notelikeus] Supabase session restore failed:', error);
      applySession(null);
    });

  const {
    data: { subscription },
  } = client.auth.onAuthStateChange((_event: AuthChangeEvent, session) => {
    applySession(session);
  });

  return () => subscription.unsubscribe();
}

import { markGuestAdoptionIntent, clearGuestAdoptionIntent } from '@/lib/local/guestAdoptionIntent';
import { useAuthStore } from '@/store/authStore';

/**
 * Completes a PKCE `?code=` redirect when the page loads from a Supabase auth email link.
 *
 * Sign-in itself is by typed one-time code, but this stays: with `flowType: 'pkce'` any auth email
 * that still carries a link (a template left at `{{ .ConfirmationURL }}`, a signup confirmation,
 * an email change or recovery) lands back here with `?code=` and must be exchanged.
 */
export async function completeSupabaseOAuthRedirect(): Promise<void> {
  const client = getSupabaseClient();
  const url = new URL(window.location.href);

  const errorParam = url.searchParams.get('error') || url.searchParams.get('error_description');
  if (errorParam) {
    clearGuestAdoptionIntent();
    url.searchParams.delete('error');
    url.searchParams.delete('error_code');
    url.searchParams.delete('error_description');
    window.history.replaceState({}, document.title, url.pathname + url.search + url.hash);
    throw new Error(errorParam);
  }

  const code = url.searchParams.get('code');
  if (!code) return;

  try {
    const { error } = await client.auth.exchangeCodeForSession(code);
    if (error) {
      clearGuestAdoptionIntent();
      throw error;
    }
  } catch (err) {
    clearGuestAdoptionIntent();
    throw err;
  } finally {
    url.searchParams.delete('code');
    url.searchParams.delete('state');
    window.history.replaceState({}, document.title, url.pathname + url.search + url.hash);
  }
}

/**
 * Emails a one-time sign-in code. Creates the account on first use.
 *
 * No `emailRedirectTo`: the user types the code back into the app, so the email does not need to
 * carry a link. The Supabase "Magic Link" and "Confirm signup" email templates must include
 * `{{ .Token }}` for the code to reach the user.
 */
export async function sendEmailSignInCodeSupabase(email: string): Promise<void> {
  const { error } = await getSupabaseClient().auth.signInWithOtp({
    email: email.trim(),
    options: { shouldCreateUser: true },
  });
  if (error) throw error;
}

/** Verifies the code sent by [sendEmailSignInCodeSupabase] and starts the session. */
export async function verifyEmailSignInCodeSupabase(email: string, code: string): Promise<void> {
  const wasGuest = useAuthStore.getState().guestMode;
  if (wasGuest) {
    markGuestAdoptionIntent('email-code');
  }
  try {
    const { error } = await getSupabaseClient().auth.verifyOtp({
      email: email.trim(),
      token: code.trim(),
      type: 'email',
    });
    if (error) {
      if (wasGuest) clearGuestAdoptionIntent();
      throw error;
    }
  } catch (error) {
    if (wasGuest) clearGuestAdoptionIntent();
    throw error;
  }
}

export async function signInWithEmailPasswordSupabase(
  email: string,
  password: string,
): Promise<void> {
  const wasGuest = useAuthStore.getState().guestMode;
  if (wasGuest) {
    markGuestAdoptionIntent('password');
  }
  try {
    const { error } = await getSupabaseClient().auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    if (error) {
      if (wasGuest) clearGuestAdoptionIntent();
      throw error;
    }
  } catch (error) {
    if (wasGuest) clearGuestAdoptionIntent();
    throw error;
  }
}

export async function createEmailPasswordAccountSupabase(
  email: string,
  password: string,
): Promise<void> {
  const wasGuest = useAuthStore.getState().guestMode;
  if (wasGuest) {
    markGuestAdoptionIntent('signup');
  }
  try {
    const client = getSupabaseClient();
    const trimmed = email.trim();
    const { error } = await client.auth.signUp({ email: trimmed, password });
    if (error?.message?.includes('already registered')) {
      await signInWithEmailPasswordSupabase(trimmed, password);
      return;
    }
    if (error) {
      if (wasGuest) clearGuestAdoptionIntent();
      throw error;
    }
  } catch (error) {
    if (wasGuest) clearGuestAdoptionIntent();
    throw error;
  }
}

export async function signOutSupabase(): Promise<void> {
  clearGuestAdoptionIntent();
  const { error } = await getSupabaseClient().auth.signOut();
  if (error) throw error;
  stopNotesRealtimeSync();
  clearLocalUserData();
}
