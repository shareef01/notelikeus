const STORAGE_KEY = 'notelikeus-last-merged-user';

/**
 * UID of the account whose cloud data was last merged into local storage, persisted across
 * reloads — the web counterpart of Android's `NoteSyncStateStore.lastMergedUserId()`.
 *
 * A `useRef` cannot do this job: it resets to null on every page load, so a session that starts
 * with another account's notes still in localStorage (second tab, shared browser profile, a
 * sign-out that failed partway) would skip the wipe and upload those notes into the new account.
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
