import {
  deleteDoc,
  getDoc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  type Unsubscribe,
  writeBatch,
  type QueryDocumentSnapshot,
  type DocumentData,
  type WriteBatch,
} from 'firebase/firestore';
import { getFirestoreDb } from '@/lib/firebase';
import {
  cloudMapToNote,
  noteToCloudMap,
  syncMetaMap,
  type FirestoreNoteDocument,
} from '@/lib/mappers/noteCloudMapper';
import {
  userNoteDocument,
  userNotesCollection,
  userSyncMetaDocument,
  userTombstoneDocument,
  userTombstonesCollection,
} from '@/lib/firestore/paths';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';
import { isCloudSyncEligible } from '@/types/note';
import { useTombstoneStore } from '@/store/tombstoneStore';
import {
  fetchCloudTombstones,
  pruneExpiredCloudTombstones,
  writeCloudTombstone,
} from '@/lib/firestore/tombstones';

const BATCH_LIMIT = 400;

export type NotesSnapshotHandler = (notes: Note[]) => void;
export type NotesErrorHandler = (error: Error) => void;

function parseNoteDoc(
  snapshot: QueryDocumentSnapshot<DocumentData>,
  labelResolver: (name: string) => Label,
): Note {
  return cloudMapToNote(snapshot.id, snapshot.data() as FirestoreNoteDocument, labelResolver);
}

/**
 * Resolver that hands out one `Label` object per distinct name for a single read, so notes sharing
 * a label share its identity.
 */
function createLabelResolver(): (name: string) => Label {
  const labelCache = new Map<string, Label>();
  return (name: string): Label => {
    const key = name.trim().toLowerCase();
    const cached = labelCache.get(key);
    if (cached) return cached;
    const label: Label = { id: `label-${key}`, name: name.trim() };
    labelCache.set(key, label);
    return label;
  };
}

/** Applies `apply` to every item, committing writes in chunks that respect Firestore's batch limit. */
async function commitInBatches<T>(
  items: readonly T[],
  apply: (batch: WriteBatch, item: T) => void,
): Promise<void> {
  for (let i = 0; i < items.length; i += BATCH_LIMIT) {
    const batch = writeBatch(getFirestoreDb());
    for (const item of items.slice(i, i + BATCH_LIMIT)) {
      apply(batch, item);
    }
    await batch.commit();
  }
}

export function subscribeToNotes(
  userId: string,
  onData: NotesSnapshotHandler,
  onError?: NotesErrorHandler,
): Unsubscribe {
  const notesQuery = query(userNotesCollection(userId), orderBy('timestamp', 'desc'));
  const resolveLabel = createLabelResolver();

  return onSnapshot(
    notesQuery,
    (snapshot) => {
      const notes = snapshot.docs.map((docSnap) => parseNoteDoc(docSnap, resolveLabel));
      onData(notes);
    },
    (error) => {
      onError?.(error);
    },
  );
}

export async function upsertNote(userId: string, note: Note): Promise<void> {
  // Costs one read per save, deliberately: this is what stops a note deleted on another device
  // from being resurrected by an in-flight save here. Debouncing saves is the way to reduce it —
  // dropping the read would trade delete propagation for the saving.
  const tombstoneSnap = await getDoc(userTombstoneDocument(userId, note.id));
  if (tombstoneSnap.exists()) {
    const raw = tombstoneSnap.data()?.deletedAt;
    const deletedAt =
      typeof raw === 'number' && Number.isFinite(raw) ? raw : Date.now();
    useTombstoneStore.getState().mergeFromCloud({ [note.id]: deletedAt });
  }

  const ref = userNoteDocument(userId, note.id);
  if (useTombstoneStore.getState().isDeleted(note.id)) {
    await deleteDoc(ref);
    return;
  }

  const existing = await getDoc(ref);
  if (existing.exists()) {
    const remote = cloudMapToNote(
      existing.id,
      existing.data() as FirestoreNoteDocument,
      createLabelResolver(),
    );
    if (!shouldUploadOverRemote(note, remote)) {
      return;
    }
  }

  await setDoc(ref, noteToCloudMap(note), { merge: true });
}

export async function deleteNote(userId: string, noteId: string): Promise<void> {
  await writeCloudTombstone(userId, noteId);
  await deleteDoc(userNoteDocument(userId, noteId));
}

/**
 * Prefers serverUpdatedAt (server-assigned) over the client-set `timestamp` for deciding whether
 * `note` should overwrite `remote`.
 *
 * Backup files are arbitrary user JSON — `cloudMapToNote` never resolves a plain JSON value into a
 * real serverUpdatedAt, only an actual Firestore `Timestamp` does, so an imported note's
 * serverUpdatedAt is always null. A hand-edited `timestamp` in that JSON must not be able to
 * masquerade as newer than a remote note that's already confirmed-synced, so if remote has a
 * serverUpdatedAt and `note` doesn't, the untrusted side loses outright — and symmetrically, a
 * confirmed local note beats an unconfirmed remote one (a legacy doc that predates this field)
 * regardless of either side's client timestamp. Only fall back to comparing client timestamps when
 * *neither* side has been confirmed by the server yet.
 *
 * Equal server stamps mean the two sides started from the same confirmed revision. A locally-edited
 * note keeps that stamp (the pending write only bumps `timestamp`), so upload iff the client clock
 * is strictly newer — matching Kotlin's `cloudWinsConflict` equal-stamp tie-break. Equal client
 * timestamps too means nothing changed, so reconcile does not rewrite the library. A strictly newer
 * remote stamp still wins outright, which is what stops a stale live save from clobbering another
 * device's edit.
 */
export function shouldUploadOverRemote(note: Note, remote: Note | undefined): boolean {
  if (!remote) return true;
  if (note.serverUpdatedAt != null && remote.serverUpdatedAt != null) {
    if (note.serverUpdatedAt !== remote.serverUpdatedAt) {
      return note.serverUpdatedAt > remote.serverUpdatedAt;
    }
    return note.timestamp > remote.timestamp;
  }
  if (remote.serverUpdatedAt != null) return false;
  if (note.serverUpdatedAt != null) return true;
  return note.timestamp >= remote.timestamp;
}

export async function uploadAllNotes(userId: string, notes: Note[]): Promise<number> {
  const cloudTombstones = await fetchCloudTombstones(userId);
  useTombstoneStore.getState().mergeFromCloud(cloudTombstones);
  const isDeleted = (id: string) => useTombstoneStore.getState().isDeleted(id);
  const eligible = notes.filter((note) => isCloudSyncEligible(note) && !isDeleted(note.id));
  if (eligible.length === 0) {
    await setDoc(userSyncMetaDocument(userId), syncMetaMap(0, 'web'), { merge: true });
    return 0;
  }

  const remoteNotes = await fetchRemoteNotes(userId);
  const remoteById = new Map(remoteNotes.map((note) => [note.id, note]));

  const toUpload = eligible.filter((note) => shouldUploadOverRemote(note, remoteById.get(note.id)));

  await commitInBatches(toUpload, (batch, note) => {
    batch.set(userNoteDocument(userId, note.id), noteToCloudMap(note), { merge: true });
  });

  await setDoc(userSyncMetaDocument(userId), syncMetaMap(eligible.length, 'web'), { merge: true });
  return toUpload.length;
}

/**
 * Last-write-wins merge, matching Kotlin's `NoteSyncEngine.cloudWinsConflict` and
 * [shouldUploadOverRemote]. Prefers server-assigned `serverUpdatedAt`; a confirmed remote beats an
 * unconfirmed local (e.g. backup import) regardless of client `timestamp`.
 */
export async function mergeRemoteNotes(localNotes: Note[], remoteNotes: Note[]): Promise<Note[]> {
  const byId = new Map<string, Note>(localNotes.map((note) => [note.id, note]));

  for (const remote of remoteNotes) {
    const local = byId.get(remote.id);
    if (!local) {
      byId.set(remote.id, remote);
      continue;
    }
    if (!shouldUploadOverRemote(local, remote)) {
      byId.set(remote.id, remote);
    }
  }

  return Array.from(byId.values());
}

export async function deleteAllCloudData(userId: string): Promise<number> {
  const snapshot = await getDocs(userNotesCollection(userId));
  let deleted = 0;

  await commitInBatches(snapshot.docs, (batch, document) => {
    batch.delete(document.ref);
    deleted++;
  });

  const tombstones = await getDocs(userTombstonesCollection(userId));
  await commitInBatches(tombstones.docs, (batch, document) => {
    batch.delete(document.ref);
  });

  // Propagates: this runs from "sign out and delete my cloud data", so leaving the sync document
  // behind while reporting a completed wipe is the one outcome the caller must not report.
  await deleteDoc(userSyncMetaDocument(userId));
  return deleted;
}

export async function fetchRemoteNotes(userId: string): Promise<Note[]> {
  const snapshot = await getDocs(query(userNotesCollection(userId), orderBy('timestamp', 'desc')));
  const resolveLabel = createLabelResolver();
  return snapshot.docs.map((docSnap) => parseNoteDoc(docSnap, resolveLabel));
}

/**
 * Merges cloud notes into local state and uploads any newer / missing local notes.
 *
 * Conflict resolution matches Android `downloadAllNotes` (see `shouldUploadOverRemote`), but
 * deletion does *not*: this deliberately has no delete-on-absence branch. Deletes still propagate,
 * via the tombstones merged at the top of this function — what's missing is Android's extra rule
 * that a note absent from the cloud, whose id a previous download recorded as present, is deleted
 * rather than re-uploaded.
 *
 * `previouslyKnownCloudIds` was previously removed because it defaulted to an empty set and no
 * caller ever passed one, leaving the branch unreachable. It is back because its stated
 * precondition now holds: the empty-cloud guard below matches Android's
 * `SuspectEmptyCloudException`, so a failed read can no longer read as a remote wipe, and
 * notesSyncService passes the id set it tracks across snapshots.
 */
export async function syncNotesWithCloud(
  userId: string,
  localNotes: Note[],
  previouslyKnownCloudIds: Set<string> = new Set(),
): Promise<{ changes: number; merged: Note[]; remoteIds: string[] }> {
  const cloudTombstones = await fetchCloudTombstones(userId);
  useTombstoneStore.getState().mergeFromCloud(cloudTombstones);

  const remoteNotes = await fetchRemoteNotes(userId);
  // Indexed once rather than scanned per local note: the loop below looked remotes up with
  // `remoteNotes.find`, making the merge O(local x remote) on every reconcile. `cloudIds` is
  // derivable from this map, but keeping the Set avoids re-deriving it on each membership test.
  const remoteById = new Map(remoteNotes.map((note) => [note.id, note]));
  const cloudIds = new Set(remoteById.keys());

  // Same guard Android NoteSyncEngine uses in every sync direction: an empty fetch when we
  // previously knew cloud note IDs is far more likely a failed-open fetch than a genuine mass
  // deletion (which leaves tombstones). Refuse the sync instead of deleting local notes or
  // pushing over the real cloud copies.
  if (remoteNotes.length === 0 && previouslyKnownCloudIds.size > 0) {
    throw new Error(
      `Cloud returned no notes but ${previouslyKnownCloudIds.size} were expected — refusing to ` +
        `delete local copies. Check the connection or sign in again.`,
    );
  }

  const isDeleted = (id: string) => useTombstoneStore.getState().isDeleted(id);
  let changes = 0;
  const droppedLocalIds = new Set<string>();

  let merged = await mergeRemoteNotes(localNotes, remoteNotes);
  merged = merged.filter((note) => !isDeleted(note.id));

  for (const localNote of localNotes) {
    if (isDeleted(localNote.id)) continue;

    if (cloudIds.has(localNote.id)) {
      if (!isCloudSyncEligible(localNote)) continue;
      const remote = remoteById.get(localNote.id);
      if (shouldUploadOverRemote(localNote, remote)) {
        await upsertNote(userId, localNote);
        merged = merged.map((note) => (note.id === localNote.id ? localNote : note));
        changes++;
      }
      continue;
    }

    // Absent from cloud: if we previously knew this id there, it was deleted elsewhere, so do
    // not re-upload it (Android deletes the local row in the same situation). The empty-cloud
    // guard above is what makes this safe to act on.
    if (previouslyKnownCloudIds.has(localNote.id)) {
      droppedLocalIds.add(localNote.id);
      useTombstoneStore.getState().markDeleted(localNote.id);
      await writeCloudTombstone(userId, localNote.id);
      changes++;
      continue;
    }

    // Never seen in the cloud and not tombstoned: treat it as never-synced and push it.
    if (isCloudSyncEligible(localNote)) {
      await upsertNote(userId, localNote);
      changes++;
    }
  }

  if (droppedLocalIds.size > 0) {
    merged = merged.filter((note) => !droppedLocalIds.has(note.id));
  }

  for (const remoteNote of remoteNotes) {
    if (isDeleted(remoteNote.id)) continue;
    if (!merged.some((note) => note.id === remoteNote.id)) {
      merged.push(remoteNote);
      changes++;
    }
  }

  const liveIds = new Set(merged.map((note) => note.id));
  const pruned = await pruneExpiredCloudTombstones(
    userId,
    useTombstoneStore.getState().deletedAtById,
    liveIds,
  );
  if (pruned.length > 0) {
    useTombstoneStore.getState().clearIds(pruned);
  }

  await touchSyncMeta(userId, merged.filter(isCloudSyncEligible).length);
  return { changes, merged, remoteIds: remoteNotes.map((note) => note.id) };
}

export async function touchSyncMeta(userId: string, noteCount: number): Promise<void> {
  await setDoc(userSyncMetaDocument(userId), {
    ...syncMetaMap(noteCount, 'web'),
    lastSyncAt: serverTimestamp(),
  }, { merge: true });
}
