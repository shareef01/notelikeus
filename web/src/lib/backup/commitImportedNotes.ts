import { replaceAllNotes } from '@/lib/local/notesLocalRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import {
  pauseRealtimeSnapshots,
  resumeRealtimeSnapshots,
} from '@/lib/notes/notesSyncService';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useNotesStore } from '@/store/notesStore';
import type { Note } from '@/types/note';

/**
 * Commits an import to IndexedDB and the in-memory store. Cloud upload is best-effort:
 * a failed upsert must not unwind the local import.
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
    const ownerId = resolveOwnerId();
    if (ownerId) {
      await replaceAllNotes(ownerId, merged);
    }
    useNotesStore.getState().setNotes(merged);
    if (!userId) return false;
    await getRemoteNotesDataSource().uploadAllNotes(userId, merged);
    return true;
  } finally {
    resumeRealtimeSnapshots();
  }
}
