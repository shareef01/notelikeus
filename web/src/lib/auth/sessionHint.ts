const STORAGE_KEY = 'notelikeus-had-session';

/**
 * Records that the last resolved auth state had a signed-in user.
 *
 * Firebase restores its session from IndexedDB asynchronously, so `onAuthStateChanged` does not
 * fire until well after first paint. Blocking on that put a "Checking your sign-in status…"
 * screen in front of every single refresh, even though the notes were already rehydrated from
 * local storage and ready to show.
 *
 * This hint lets the app render straight away for someone who was signed in last time, and fall
 * back to the blocking gate only when there is genuinely no idea. It is a rendering hint, not an
 * authorization signal — nothing is trusted because of it, and Firestore rules still gate all
 * data access on a real token.
 */
export function rememberSignedIn(): void {
  try {
    localStorage.setItem(STORAGE_KEY, '1');
  } catch {
    // Private mode / quota — falls back to the blocking gate, which is correct but slower.
  }
}

export function forgetSignedIn(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

export function hadSessionLastLoad(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === '1';
  } catch {
    return false;
  }
}

export const SESSION_HINT_STORAGE_KEY = STORAGE_KEY;
