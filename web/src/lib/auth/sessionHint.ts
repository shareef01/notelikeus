const STORAGE_KEY = 'notelikeus-had-session';

/**
 * Records that the last resolved auth state had a signed-in user.
 *
 * Supabase restores its session asynchronously. This hint lets the app render straight away for
 * someone who was signed in last time, and fall back to the blocking gate only when there is
 * genuinely no idea. It is a rendering hint, not an authorization signal — nothing is trusted
 * because of it, and RLS still gates all data access on a real token.
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

const GUEST_STORAGE_KEY = 'notelikeus-guest-mode';

/**
 * Records that this browser is using the app without an account, so a reload returns straight to
 * the guest's notes. Without it every reload showed the sign-in gate again, and a guest's notes —
 * still in IndexedDB — looked gone until they pressed "Continue without an account" a second time.
 * Like the session hint, it only decides what to render first; it grants nothing.
 */
export function rememberGuestMode(): void {
  try {
    localStorage.setItem(GUEST_STORAGE_KEY, '1');
  } catch {
    // Private mode / quota: the gate shows again on reload, which loses nothing.
  }
}

export function forgetGuestMode(): void {
  try {
    localStorage.removeItem(GUEST_STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** Whether the last load ended in guest mode. A signed-in session hint always takes precedence. */
export function wasGuestLastLoad(): boolean {
  try {
    return localStorage.getItem(GUEST_STORAGE_KEY) === '1' && !hadSessionLastLoad();
  } catch {
    return false;
  }
}

export const GUEST_MODE_STORAGE_KEY = GUEST_STORAGE_KEY;
