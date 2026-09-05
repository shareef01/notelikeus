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

/** Completes an OAuth redirect when the page reloads after Google sign-in. */
export async function completeSupabaseOAuthRedirect(): Promise<void> {
  const client = getSupabaseClient();
  const url = new URL(window.location.href);
  const code = url.searchParams.get('code');
  if (!code) return;

  const { error } = await client.auth.exchangeCodeForSession(code);
  if (error) throw error;

  url.searchParams.delete('code');
  url.searchParams.delete('state');
  window.history.replaceState({}, document.title, url.pathname + url.search + url.hash);
}

export async function signInWithGoogleSupabase(): Promise<void> {
  const { error } = await getSupabaseClient().auth.signInWithOAuth({
    provider: 'google',
    options: {
      redirectTo: window.location.origin,
    },
  });
  if (error) throw error;
}

export async function signInWithEmailPasswordSupabase(
  email: string,
  password: string,
): Promise<void> {
  const { error } = await getSupabaseClient().auth.signInWithPassword({
    email: email.trim(),
    password,
  });
  if (error) throw error;
}

export async function createEmailPasswordAccountSupabase(
  email: string,
  password: string,
): Promise<void> {
  const client = getSupabaseClient();
  const trimmed = email.trim();
  const { error } = await client.auth.signUp({ email: trimmed, password });
  if (error?.message?.includes('already registered')) {
    await signInWithEmailPasswordSupabase(trimmed, password);
    return;
  }
  if (error) throw error;
}

export async function signOutSupabase(): Promise<void> {
  const { error } = await getSupabaseClient().auth.signOut();
  if (error) throw error;
  stopNotesRealtimeSync();
  clearLocalUserData();
}
