import {
  getOwnerMeta,
  listNotes,
  replaceAllNotes,
  setOwnerMeta,
} from '@/lib/local/notesLocalRepository';
import { reconcileLocalAndRemoteSnapshot } from '@/lib/notes/reconcileLocalAndRemoteSnapshot';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { isSuspiciousEmptySnapshotError, sanitizeSyncErrorMessage } from '@/lib/remote/remoteErrors';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
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

/** Persist the merged live set to memory and IndexedDB as one complete mirror. */
export async function applyMergedLiveSet(ownerId: string, merged: Note[]): Promise<void> {
  useNotesStore.getState().setNotes(merged);
  await replaceAllNotes(ownerId, merged);
}

/**
 * First signed-in session for this owner: pull an authoritative remote snapshot,
 * reconcile with any local-only notes already in the namespace, then mirror the merged
 * live set into IndexedDB. Idempotent once `remoteHydrated` is set for the owner.
 */
export async function hydrateIndexedDbFromRemote(userId: string): Promise<void> {
  const meta = await getOwnerMeta(userId);
  if (meta?.remoteHydrated ?? meta?.firebaseHydrated) {
    await loadLocalNotesIntoStore(userId);
    return;
  }

  const remote = getRemoteNotesDataSource();
  const snapshot = await remote.fetchAllNotes(userId);
  const local = await listNotes(userId);
  const knownRemoteIds = new Set(meta?.knownRemoteIds ?? []);

  try {
    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: local,
      remoteNotes: snapshot,
      remoteTombstones: {},
      knownRemoteIds,
      isDeleted: (id) => useTombstoneStore.getState().isDeleted(id),
    });
    for (const id of result.newlyDeletedIds) {
      useTombstoneStore.getState().markDeleted(id);
    }
    await applyMergedLiveSet(userId, result.merged);
    await setOwnerMeta(userId, {
      remoteHydrated: true,
      firebaseHydrated: true,
      hydratedAt: Date.now(),
      knownRemoteIds: [...result.nextKnownRemoteIds],
    });
  } catch (error) {
    if (isSuspiciousEmptySnapshotError(error)) {
      await loadLocalNotesIntoStore(userId);
      useNotesStore.getState().setError(sanitizeSyncErrorMessage(error));
      return;
    }
    throw error;
  }
}
