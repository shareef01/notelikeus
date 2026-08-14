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
      const { notes, unrecoverable } = await unlockPersistedNotes(rawNotes as MaybeLockedNote[]);
      // Only notes whose text we actually hold. Uploading a still-encrypted note would publish a
      // blank copy that then wins over the real one everywhere.
      if (notes.length > 0) {
        await syncNotesWithCloud(userId, notes);
      }
      if (unrecoverable.length > 0) {
        // Keep the legacy key. It holds the only copy of these notes' ciphertext, and the reason
        // for the failure may be transient (a second tab holding IndexedDB open, private
        // browsing) or specific to this device — deleting it here is unrecoverable, whereas
        // leaving it costs one no-op read per launch and lets a later launch, or the device that
        // still has the key, complete the migration.
        console.warn(
          `[Notelikeus] ${unrecoverable.length} previously hidden note(s) could not be unlocked ` +
            `on this device; keeping the local copy so a later launch can retry.`,
        );
        return;
      }
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
