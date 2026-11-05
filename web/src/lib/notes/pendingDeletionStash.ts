import {
  isTombstoneSnapshotEmpty,
  mergeTombstoneSnapshots,
  normalizeTombstoneSnapshot,
  snapshotTombstones,
  useTombstoneStore,
  type TombstoneSnapshot,
} from '@/store/tombstoneStore';

/**
 * Per-account storage for deletion intent that is not in use (AUDIT-004).
 *
 * The tombstone store is one live record for whichever account is active. When another account
 * or guest mode takes over, the previous account's tombstones are moved here, under that account's
 * id, instead of being thrown away: a note deleted offline exists nowhere else, so dropping its
 * tombstone brings the note back the next time that account syncs. They move back into the live
 * store when the same account signs in again.
 */
export const PENDING_DELETION_STASH_PREFIX = 'notelikeus-deleted-notes:';

function stashKey(ownerId: string): string {
  return `${PENDING_DELETION_STASH_PREFIX}${ownerId}`;
}

function readStash(ownerId: string): TombstoneSnapshot | null {
  let raw: string | null;
  try {
    raw = localStorage.getItem(stashKey(ownerId));
  } catch {
    return null;
  }
  if (raw == null) return null;
  try {
    return normalizeTombstoneSnapshot(JSON.parse(raw));
  } catch {
    return null;
  }
}

/**
 * Moves the live tombstones into `ownerId`'s stash and empties the live store.
 *
 * Returns false when the stash could not be written (storage full or blocked). The live store is
 * emptied either way: another account's deletions must never be applied to the next account.
 */
export function stashPendingDeletions(ownerId: string): boolean {
  const live = snapshotTombstones();
  let stored = true;
  if (!isTombstoneSnapshotEmpty(live)) {
    const existing = readStash(ownerId);
    const combined = existing ? mergeTombstoneSnapshots(live, existing) : live;
    try {
      localStorage.setItem(stashKey(ownerId), JSON.stringify(combined));
    } catch (error) {
      stored = false;
      console.warn("[Notelikeus] Couldn't keep the previous account's pending deletions:", error);
    }
  }
  useTombstoneStore.getState().reset();
  return stored;
}

/** Moves `ownerId`'s stash, if any, back into the live store, which must already be that account's. */
export function restorePendingDeletions(ownerId: string): void {
  const stashed = readStash(ownerId);
  if (stashed == null) return;
  if (!isTombstoneSnapshotEmpty(stashed)) {
    useTombstoneStore.setState(mergeTombstoneSnapshots(snapshotTombstones(), stashed));
  }
  try {
    localStorage.removeItem(stashKey(ownerId));
  } catch {
    // The stash is merged in; a copy left behind merges again harmlessly next time.
  }
}

/** Removes every account's stash. Only for an explicit "clear local data". */
export function clearAllPendingDeletionStashes(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (key?.startsWith(PENDING_DELETION_STASH_PREFIX)) keys.push(key);
    }
    for (const key of keys) localStorage.removeItem(key);
  } catch {
    // ignore
  }
}
