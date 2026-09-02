import { clearLocalUserData } from '@/lib/bootstrap';
import { deleteAllCloudData } from '@/lib/firestore/notesRepository';
import { stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { getFirebaseAuth, initFirebase, purgeFirestoreCache } from '@/lib/firebase';
import { signInWithGoogleSupabase, signOutSupabase } from '@/lib/auth/supabaseAuth';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';
import { deleteAllSupabaseCloudData } from '@/lib/supabase/deleteAllUserCloudData';
import {
  GoogleAuthProvider,
  getRedirectResult,
  signInWithPopup,
  signInWithRedirect,
  signOut,
} from 'firebase/auth';
import { FirebaseError } from 'firebase/app';

// Environments where a popup can't open at all (blocked, or an in-app/standalone
// browser that doesn't support window.open-based OAuth) — retry via full-page
// redirect instead. Deliberately excludes popup-closed-by-user/cancelled codes,
// since those mean the user intentionally backed out and shouldn't be redirected.
const REDIRECT_FALLBACK_CODES = new Set([
  'auth/popup-blocked',
  'auth/operation-not-supported-in-this-environment',
]);

export async function signInWithGoogle(): Promise<void> {
  if (isSupabaseBackendEnabled()) {
    await signInWithGoogleSupabase();
    return;
  }
  initFirebase();
  const auth = getFirebaseAuth();
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  try {
    await signInWithPopup(auth, provider);
  } catch (error) {
    if (error instanceof FirebaseError && REDIRECT_FALLBACK_CODES.has(error.code)) {
      await signInWithRedirect(auth, provider);
      return;
    }
    throw error;
  }
}

/** Completes a signInWithRedirect flow after the page reloads. Call once at startup. */
export async function completeGoogleRedirectSignIn(): Promise<void> {
  initFirebase();
  await getRedirectResult(getFirebaseAuth());
}

export async function signOutGoogle(options: { deleteCloudData?: boolean } = {}): Promise<void> {
  if (isSupabaseBackendEnabled()) {
    if (options.deleteCloudData) {
      await deleteAllSupabaseCloudData();
    }
    await signOutSupabase();
    return;
  }
  initFirebase();
  const auth = getFirebaseAuth();
  const userId = auth.currentUser?.uid;
  const { deleteCloudData = false } = options;

  if (deleteCloudData && userId) {
    await deleteAllCloudData(userId);
  }

  await signOut(auth);
  stopNotesRealtimeSync();
  clearLocalUserData();
  await purgeFirestoreCache();
}
