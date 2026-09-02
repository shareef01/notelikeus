import {
  getOwnerMeta,
  listNotes,
  putNotes,
  setOwnerMeta,
} from '@/lib/local/notesLocalRepository';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useNotesStore } from '@/store/notesStore';
import type { Note } from '@/types/note';

/**
 * Loads notes from IndexedDB into the in-memory store.
 * Safe to call on every owner change / app resume.
 */
export async function loadLocalNotesIntoStore(ownerId: string): Promise<Note[]> {
  const notes = await listNotes(ownerId);
  useNotesStore.getState().setNotes(notes);
  return notes;
}

/**
 * First signed-in session for this owner: pull an authoritative Firebase snapshot,
 * populate IndexedDB, then mirror into the UI store. Idempotent once `firebaseHydrated`
 * is set for the owner.
 */
export async function hydrateIndexedDbFromFirebase(userId: string): Promise<void> {
  const meta = await getOwnerMeta(userId);
  if (meta?.firebaseHydrated) {
    await loadLocalNotesIntoStore(userId);
    return;
  }

  const remote = getRemoteNotesDataSource();
  const snapshot = await remote.fetchAllNotes(userId);
  await putNotes(userId, snapshot);
  await setOwnerMeta(userId, { firebaseHydrated: true, hydratedAt: Date.now() });
  useNotesStore.getState().setNotes(snapshot);
}
