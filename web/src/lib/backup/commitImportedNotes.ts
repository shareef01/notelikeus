import {
  pauseRealtimeSnapshots,
  resumeRealtimeSnapshots,
} from '@/lib/notes/notesSyncService';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { putNotes } from '@/lib/local/notesLocalRepository';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { useNotesStore } from '@/store/notesStore';
import type { Note } from '@/types/note';

/**
 * Commits an import to durable local storage (IndexedDB) ÔÇö and to the active remote when signed in ÔÇö
 * without letting a realtime snapshot of the *pre-import* cloud replace the merged library first.
 *
 * For guest users, the merged library is durably persisted under GUEST_OWNER_ID before updating memory.
 *
 * Upload runs before local persistence and store updates so the next snapshot already contains the new ids.
 * Snapshots that arrive during the upload are dropped rather than applied.
 *
 * Returns whether the library was written to the cloud. A failed upload or failed persistence does not
 * update the store, so the user can retry rather than losing data.
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
    await putNotes(GUEST_OWNER_ID, merged);
    useNotesStore.getState().setNotes(merged);
    return false;
  } finally {
    resumeRealtimeSnapshots();
  }
}
