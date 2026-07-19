import { clearLocalUserData } from '@/lib/bootstrap';
import { deleteAllCloudData } from '@/lib/firestore/notesRepository';
import { resetCloudMergeState } from '@/lib/notes/notesSyncService';
import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
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

export async function signOutGoogle(deleteCloudData = false): Promise<void> {
  initFirebase();
  const auth = getFirebaseAuth();
  const userId = auth.currentUser?.uid;

  if (deleteCloudData && userId) {
    await deleteAllCloudData(userId);
  }

  await signOut(auth);
  resetCloudMergeState();
  clearLocalUserData();
}
