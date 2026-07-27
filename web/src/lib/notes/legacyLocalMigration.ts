import { syncNotesWithCloud } from '@/lib/firestore/notesRepository';
import { unlockPersistedNotes, type MaybeLockedNote } from '@/lib/crypto/unlockMigration';

const LEGACY_NOTES_KEY = 'notelikeus-notes';

/**
 * One-time migration for the local-storage-backed notes store this app used to have. Notes now
 * live in Firestore only (see notesStore.ts), so on the first launch after that change, whatever
 * was still sitting in the old key — most commonly edits made while the now-removed "auto-sync"
 * toggle was off — gets pushed to this account's Firestore data before the key is discarded.
 *
 * Safe to call on every sign-in: it's a no-op once the legacy key is gone.
 */
export async function migrateLegacyLocalNotes(userId: string): Promise<void> {
  let raw: string | null;
  try {
    raw = localStorage.getItem(LEGACY_NOTES_KEY);
  } catch {
    return;
  }
  if (!raw) return;

  try {
    const parsed = JSON.parse(raw) as { state?: { notes?: unknown } };
    const rawNotes = Array.isArray(parsed?.state?.notes) ? parsed.state.notes : [];
    if (rawNotes.length > 0) {
      const notes = await unlockPersistedNotes(rawNotes as MaybeLockedNote[]);
      await syncNotesWithCloud(userId, notes);
    }
  } catch (error) {
    console.error('[Notelikeus] Legacy local-notes migration failed, will retry next launch:', error);
    return;
  }

  try {
    localStorage.removeItem(LEGACY_NOTES_KEY);
  } catch {
    // ignore
  }
}

export const LEGACY_NOTES_STORAGE_KEY = LEGACY_NOTES_KEY;
