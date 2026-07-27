const STORAGE_KEY = 'notelikeus-last-merged-user';

/**
 * UID of the account this device last synced as, persisted across reloads — the web counterpart
 * of Android's `NoteSyncStateStore.lastMergedUserId()`. Used to tell a genuine account switch
 * (Google account A signs out, account B signs in, without ever going through `signOutGoogle()`
 * — e.g. a second tab of the same browser profile) apart from an ordinary page reload or first
 * mount while still signed in as the same account.
 *
 * A `useRef` cannot do this job: it resets to null on every page load, so a session that starts
 * with another account's local data still around (second tab, shared browser profile, a
 * sign-out that failed partway) would skip the wipe and merge that stale data into the new
 * account instead.
 */
export function loadLastMergedUserId(): string | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value && value.length > 0 ? value : null;
  } catch {
    return null;
  }
}

export function saveLastMergedUserId(userId: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, userId);
  } catch {
    // Quota / private mode — the in-memory guard still covers this session.
  }
}

export function clearLastMergedUserId(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

export const LAST_MERGED_USER_STORAGE_KEY = STORAGE_KEY;
