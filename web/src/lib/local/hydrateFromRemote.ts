import {
  deleteNote,
  getOwnerMeta,
  listNotes,
  putNotes,
  setOwnerMeta,
} from '@/lib/local/notesLocalRepository';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';

/**
 * Loads notes from IndexedDB into the in-memory store.
 * Suppresses tombstoned notes and cleans stale tombstoned rows so the mirror converges.
 * Safe to call on every owner change / app resume.
 */
export async function loadLocalNotesIntoStore(ownerId: string): Promise<Note[]> {
  const notes = await listNotes(ownerId);
  const isDeleted = useTombstoneStore.getState().isDeleted;
  const liveNotes = notes.filter((note) => !isDeleted(note.id));

  const staleNotes = notes.filter((note) => isDeleted(note.id));
  if (staleNotes.length > 0) {
    void Promise.all(staleNotes.map((note) => deleteNote(ownerId, note.id))).catch((error) => {
      console.warn('[Notelikeus] Cleaning stale tombstoned notes from IndexedDB failed:', error);
    });
  }

  useNotesStore.getState().setNotes(liveNotes);
  return liveNotes;
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

  // `putNotes` upserts; it does not delete local-only rows. The UI store is what the upload
  // path reads, so those extras must stay visible. An empty snapshot is also not authoritative
  // over a populated local namespace (Firebase→Supabase migration, or the wrong account).
  const local = await listNotes(userId);
  const isDeleted = useTombstoneStore.getState().isDeleted;
  const liveLocal = local.filter((note) => !isDeleted(note.id));
  if (snapshot.length === 0) {
    if (liveLocal.length > 0) {
      useNotesStore.getState().setNotes(liveLocal);
      return;
    }
    useNotesStore.getState().setNotes([]);
    return;
  }

  const snapshotIds = new Set(snapshot.map((note) => note.id));
  const extras = liveLocal.filter((note) => !snapshotIds.has(note.id));
  useNotesStore.getState().setNotes([...snapshot, ...extras]);
}
