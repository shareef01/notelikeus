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

const PERSISTED_STORE_KEYS = ['notelikeus-notes', 'notelikeus-settings', 'notelikeus-ui'];

function clearLocalStores(): void {
  for (const key of PERSISTED_STORE_KEYS) {
    try {
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}

/** PWA / mobile browsers often block Google popups — use full-page redirect instead. */
export function shouldUseRedirectSignIn(): boolean {
  if (typeof window === 'undefined') return false;

  const isStandalone =
    window.matchMedia('(display-mode: standalone)').matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true;
  const isMobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);

  return isStandalone || isMobile;
}

function createGoogleProvider(): GoogleAuthProvider {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  return provider;
}

/** Call once on startup to finish a redirect-based Google sign-in. */
export async function completeRedirectSignInIfNeeded(): Promise<void> {
  initFirebase();
  const auth = getFirebaseAuth();
  await getRedirectResult(auth);
}

export async function signInWithGoogle(): Promise<void> {
  initFirebase();
  const auth = getFirebaseAuth();
  const provider = createGoogleProvider();

  if (shouldUseRedirectSignIn()) {
    await signInWithRedirect(auth, provider);
    return;
  }

  await signInWithPopup(auth, provider);
}

export async function signOutGoogle(deleteCloudData = false): Promise<void> {
  initFirebase();
  const auth = getFirebaseAuth();
  const userId = auth.currentUser?.uid;

  if (deleteCloudData && userId) {
    await deleteAllCloudData(userId);
  }

  // Clear all locally-persisted data so the next sign-in starts fresh.
  // This prevents old notes from being uploaded to a different account.
  clearLocalStores();
  if (userId) {
    resetCloudMergeState(userId);
  } else {
    resetCloudMergeState();
  }

  await signOut(auth);
}
