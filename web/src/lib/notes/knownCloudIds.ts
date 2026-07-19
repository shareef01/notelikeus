const STORAGE_KEY = 'notelikeus-known-cloud-ids';

/** Persist Android-parity "once seen in cloud" IDs across reloads. */
export function loadKnownCloudIds(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.filter((id): id is string => typeof id === 'string' && id.length > 0));
  } catch {
    return new Set();
  }
}

export function saveKnownCloudIds(ids: Set<string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // Quota / private mode — in-memory still works for the session.
  }
}

export function clearKnownCloudIds(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

export const KNOWN_CLOUD_IDS_STORAGE_KEY = STORAGE_KEY;
