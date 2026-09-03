import {
  pauseRealtimeSnapshots,
  resumeRealtimeSnapshots,
} from '@/lib/notes/notesSyncService';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useNotesStore } from '@/store/notesStore';
import type { Note } from '@/types/note';

/**
 * Commits an import to the in-memory store — and to the active remote when signed in —
 * without letting a realtime snapshot of the *pre-import* cloud replace the merged library first.
 *
 * Upload runs before `setNotes` so the next snapshot already contains the new ids. Snapshots
 * that arrive during the upload are dropped rather than applied.
 *
 * Returns whether the library was written to the cloud. A failed upload does not update the
 * store, so the user can retry rather than watching the import vanish on the next snapshot.
 */
export async function commitImportedNotes(
  merged: Note[],
  notesImported: number,
  userId: string | undefined,
): Promise<boolean> {
  if (notesImported <= 0) {
    useNotesStore.getState().setNotes(merged);
    return false;
  }

  pauseRealtimeSnapshots();
  try {
    if (userId) {
      await getRemoteNotesDataSource().uploadAllNotes(userId, merged);
      useNotesStore.getState().setNotes(merged);
      return true;
    }
    useNotesStore.getState().setNotes(merged);
    return false;
  } finally {
    resumeRealtimeSnapshots();
  }
}
