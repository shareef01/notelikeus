import type { Note } from '@/types/note';

const STORAGE_PREFIX = 'notelikeus-known-cloud-ids:';

export function loadKnownCloudIds(userId: string): Set<string> {
  try {
    const raw = localStorage.getItem(`${STORAGE_PREFIX}${userId}`);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.filter((value): value is string => typeof value === 'string'));
  } catch {
    return new Set();
  }
}

export function saveKnownCloudIds(userId: string, cloudIds: Set<string>): void {
  localStorage.setItem(`${STORAGE_PREFIX}${userId}`, JSON.stringify([...cloudIds]));
}

export function clearKnownCloudIds(userId: string): void {
  localStorage.removeItem(`${STORAGE_PREFIX}${userId}`);
}

/**
 * Drop notes that were previously synced to cloud but are absent from the latest remote snapshot.
 */
export function applyRemoteDeletions(
  notes: Note[],
  previousKnownCloudIds: Set<string>,
  currentRemoteCloudIds: Set<string>,
): Note[] {
  if (previousKnownCloudIds.size === 0) return notes;
  return notes.filter((note) => {
    if (note.isLocked) return true;
    if (!previousKnownCloudIds.has(note.cloudId)) return true;
    return currentRemoteCloudIds.has(note.cloudId);
  });
}
