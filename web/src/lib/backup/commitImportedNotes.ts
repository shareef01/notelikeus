import {
  pauseRealtimeSnapshots,
  resumeRealtimeSnapshots,
} from '@/lib/notes/notesSyncService';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { listPendingPushes, putLocalEdits, putNotes } from '@/lib/local/notesLocalRepository';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { useNotesStore } from '@/store/notesStore';
import type { Note } from '@/types/note';

/**
 * Where an import ended up.
 *
 * - `synced`: saved here and every imported note is in the cloud.
 * - `pending`: saved here; some notes have not reached the cloud yet and the next sync sends them.
 * - `local`: saved here only, because no one is signed in.
 */
export type ImportCommitOutcome = 'synced' | 'pending' | 'local';

/**
 * Commits an import: the merged library becomes the local library, and when signed in the imported
 * notes are queued for the cloud like any other edit.
 *
 * Signed in, the imported notes (the last [notesImported] of [merged]) are written to IndexedDB as
 * pending local edits in one transaction, before anything is sent (AUDIT-005). This used to upload
 * the library first and write locally only after: an upload that failed partway left some notes in
 * the cloud and none here, the caller then released their staged images, and a retry imported the
 * uploaded notes a second time under new ids. Now a failed upload leaves ordinary pending edits,
 * which the regular push retries without duplicating anything, and their images stay staged.
 *
 * Throws only when the local write fails; the store is then left as it was.
 */
export async function commitImportedNotes(
  merged: Note[],
  notesImported: number,
  userId: string | undefined,
): Promise<ImportCommitOutcome> {
  if (notesImported <= 0) {
    useNotesStore.getState().setNotes(merged);
    return userId ? 'synced' : 'local';
  }

  // No snapshot of the pre-import cloud may replace the merged library while it is being written.
  // Afterwards the pending markers protect the imported notes on their own (F-06).
  pauseRealtimeSnapshots();
  try {
    if (!userId) {
      await putNotes(GUEST_OWNER_ID, merged);
      useNotesStore.getState().setNotes(merged);
      return 'local';
    }
    await putLocalEdits(userId, merged.slice(merged.length - notesImported));
    useNotesStore.getState().setNotes(merged);
  } finally {
    resumeRealtimeSnapshots();
  }

  const importedIds = merged.slice(merged.length - notesImported).map((note) => note.id);
  try {
    await getRemoteNotesDataSource().pushPendingEdits?.(userId);
  } catch (error) {
    // Saved and queued; the next sync pushes what did not go.
    console.warn('[Notelikeus] Imported notes are saved on this device; their upload will be retried:', error);
  }
  try {
    const pending = await listPendingPushes(userId);
    return importedIds.some((id) => id in pending) ? 'pending' : 'synced';
  } catch {
    return 'pending';
  }
}
