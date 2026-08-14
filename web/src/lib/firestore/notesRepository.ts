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

export function subscribeToNotes(
  userId: string,
  onData: NotesSnapshotHandler,
  onError?: NotesErrorHandler,
): Unsubscribe {
  const notesQuery = query(userNotesCollection(userId), orderBy('timestamp', 'desc'));
  const labelCache = new Map<string, Label>();
  const resolveLabel = (name: string): Label => {
    const key = name.trim().toLowerCase();
    const cached = labelCache.get(key);
    if (cached) return cached;
    const label: Label = { id: `label-${key}`, name: name.trim() };
    labelCache.set(key, label);
    return label;
  };

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

  await setDoc(ref, noteToCloudMap(note), { merge: true });
}

export async function deleteNote(userId: string, noteId: string): Promise<void> {
  await writeCloudTombstone(userId, noteId);
  await deleteDoc(userNoteDocument(userId, noteId));
}

/**
 * Prefers serverUpdatedAt (server-assigned) over the client-set `timestamp` for deciding whether
 * `note` should overwrite `remote`. Backup files are arbitrary user JSON — `cloudMapToNote` never
 * resolves a plain JSON value into a real serverUpdatedAt, only an actual Firestore `Timestamp`
 * does, so an imported note's serverUpdatedAt is always null. A hand-edited `timestamp` in that
 * JSON must not be able to masquerade as newer than a remote note that's already confirmed-
 * synced, so if remote has a serverUpdatedAt and `note` doesn't, the untrusted side loses
 * outright — and symmetrically, a confirmed local note beats an unconfirmed remote one (a legacy
 * doc that predates this field) regardless of either side's client timestamp. Only fall back to
 * comparing client timestamps when *neither* side has been confirmed by the server yet.
 *
 * Equal server stamps mean the two sides are the same confirmed revision, so there is nothing to
 * upload: the tie goes to the cloud, matching Kotlin's `NoteSyncEngine.cloudWinsConflict`. This
 * used to be `>=`, and because local notes reach the store through the same `cloudMapToNote` that
 * `fetchRemoteNotes` uses, the two stamps are *always* equal in steady state — so the predicate
 * returned true for every note and `syncNotesWithCloud` re-uploaded the entire library (a tombstone
 * read plus a write each) on every reconcile. A locally-edited note is unaffected: its pending
 * write leaves serverUpdatedAt null until the server confirms it, which is handled below.
 */
export function shouldUploadOverRemote(note: Note, remote: Note | undefined): boolean {
  if (!remote) return true;
  if (note.serverUpdatedAt != null && remote.serverUpdatedAt != null) {
    return note.serverUpdatedAt > remote.serverUpdatedAt;
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

  const db = getFirestoreDb();
  for (let i = 0; i < toUpload.length; i += BATCH_LIMIT) {
    const chunk = toUpload.slice(i, i + BATCH_LIMIT);
    const batch = writeBatch(db);
    for (const note of chunk) {
      const payload = noteToCloudMap(note);
      batch.set(userNoteDocument(userId, note.id), payload, { merge: true });
    }
    await batch.commit();
  }

  await setDoc(userSyncMetaDocument(userId), syncMetaMap(eligible.length, 'web'), { merge: true });
  return toUpload.length;
}

/**
 * Last-write-wins merge, matching Android FirebaseNoteSync and [shouldUploadOverRemote].
 * Prefers server-assigned `serverUpdatedAt`; a confirmed remote beats an unconfirmed local
 * (e.g. backup import) regardless of client `timestamp`.
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

  for (let i = 0; i < snapshot.docs.length; i += BATCH_LIMIT) {
    const chunk = snapshot.docs.slice(i, i + BATCH_LIMIT);
    const batch = writeBatch(getFirestoreDb());
    for (const document of chunk) {
      batch.delete(document.ref);
      deleted++;
    }
    await batch.commit();
  }

  const tombstones = await getDocs(userTombstonesCollection(userId));
  for (let i = 0; i < tombstones.docs.length; i += BATCH_LIMIT) {
    const chunk = tombstones.docs.slice(i, i + BATCH_LIMIT);
    const batch = writeBatch(getFirestoreDb());
    for (const document of chunk) {
      batch.delete(document.ref);
    }
    await batch.commit();
  }

  await deleteDoc(userSyncMetaDocument(userId)).catch(() => undefined);
  return deleted;
}

export async function fetchRemoteNotes(userId: string): Promise<Note[]> {
  const snapshot = await getDocs(query(userNotesCollection(userId), orderBy('timestamp', 'desc')));
  const labelCache = new Map<string, Label>();
  const resolveLabel = (name: string): Label => {
    const key = name.trim().toLowerCase();
    const cached = labelCache.get(key);
    if (cached) return cached;
    const label: Label = { id: `label-${key}`, name: name.trim() };
    labelCache.set(key, label);
    return label;
  };
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
 * There used to be a `previouslyKnownCloudIds` parameter for exactly that, but it defaulted to an
 * empty set and no caller ever passed one, so the branch was unreachable — the docstring claimed a
 * guarantee the code did not provide. Restoring it needs the empty-cloud guard Android carries
 * first (`SuspectEmptyCloudException`), or a failed read looks like a remote wipe and takes the
 * local notes with it. Until then the honest behaviour is the one below: the only gap is a note
 * reappearing after its tombstone is pruned at TTL.
 */
export async function syncNotesWithCloud(
  userId: string,
  localNotes: Note[],
): Promise<{ changes: number; merged: Note[]; remoteIds: string[] }> {
  const cloudTombstones = await fetchCloudTombstones(userId);
  useTombstoneStore.getState().mergeFromCloud(cloudTombstones);

  const remoteNotes = await fetchRemoteNotes(userId);
  const cloudIds = new Set(remoteNotes.map((note) => note.id));
  const isDeleted = (id: string) => useTombstoneStore.getState().isDeleted(id);
  let changes = 0;

  let merged = await mergeRemoteNotes(localNotes, remoteNotes);
  merged = merged.filter((note) => !isDeleted(note.id));

  for (const localNote of localNotes) {
    if (isDeleted(localNote.id)) continue;

    if (cloudIds.has(localNote.id)) {
      if (!isCloudSyncEligible(localNote)) continue;
      const remote = remoteNotes.find((note) => note.id === localNote.id);
      if (shouldUploadOverRemote(localNote, remote)) {
        await upsertNote(userId, localNote);
        merged = merged.map((note) => (note.id === localNote.id ? localNote : note));
        changes++;
      }
      continue;
    }

    // Absent from cloud and not tombstoned: treat it as never-synced and push it. See the note on
    // delete-on-absence in this function's docstring for why absence alone is not read as deletion.
    if (isCloudSyncEligible(localNote)) {
      await upsertNote(userId, localNote);
      changes++;
    }
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
