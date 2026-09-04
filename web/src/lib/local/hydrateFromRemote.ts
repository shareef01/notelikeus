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
 * First signed-in session for this owner: pull an authoritative remote snapshot,
 * populate IndexedDB, then mirror into the UI store. Idempotent once `remoteHydrated`
 * is set for the owner.
 */
export async function hydrateIndexedDbFromRemote(userId: string): Promise<void> {
  const meta = await getOwnerMeta(userId);
  if (meta?.remoteHydrated ?? meta?.firebaseHydrated) {
    await loadLocalNotesIntoStore(userId);
    return;
  }

  const remote = getRemoteNotesDataSource();
  const snapshot = await remote.fetchAllNotes(userId);
  await putNotes(userId, snapshot);
  await setOwnerMeta(userId, {
    remoteHydrated: true,
    firebaseHydrated: true,
    hydratedAt: Date.now(),
  });

  // An empty snapshot is not authoritative over a populated local namespace. A Firebase→Supabase
  // migration puts the user's whole library under the Supabase owner id *before* anything is
  // uploaded, so the first snapshot legitimately answers with zero notes; the same shape appears
  // when the wrong account is signed in. Showing the local notes keeps them on screen and keeps
  // them in the in-memory store, which is what the upload path reads — blanking it here would
  // strand them in IndexedDB, invisible and never pushed.
  if (snapshot.length === 0) {
    const local = await listNotes(userId);
    if (local.length > 0) {
      useNotesStore.getState().setNotes(local);
      return;
    }
  }

  useNotesStore.getState().setNotes(snapshot);
}

/** @deprecated Use hydrateIndexedDbFromRemote */
export async function hydrateIndexedDbFromFirebase(userId: string): Promise<void> {
  return hydrateIndexedDbFromRemote(userId);
}
