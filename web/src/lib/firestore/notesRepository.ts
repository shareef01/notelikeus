import {
  deleteDoc,
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
  noteToFirestorePayload,
  syncMetaMap,
  type FirestoreNoteDocument,
} from '@/lib/mappers/noteCloudMapper';
import {
  userNoteDocument,
  userNotesCollection,
  userSyncMetaDocument,
} from '@/lib/firestore/paths';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';
import { isCloudSyncEligible } from '@/types/note';

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
  const payload = noteToFirestorePayload(note);
  const ref = userNoteDocument(userId, note.id);
  if (!payload) {
    await deleteDoc(ref);
    return;
  }
  await setDoc(ref, payload, { merge: true });
}

export async function deleteNote(userId: string, noteId: string): Promise<void> {
  await deleteDoc(userNoteDocument(userId, noteId));
}

export async function uploadAllNotes(userId: string, notes: Note[]): Promise<number> {
  const eligible = notes.filter(isCloudSyncEligible);
  if (eligible.length === 0) {
    await setDoc(userSyncMetaDocument(userId), syncMetaMap(0, 'web'), { merge: true });
    return 0;
  }

  const db = getFirestoreDb();
  for (let i = 0; i < eligible.length; i += BATCH_LIMIT) {
    const chunk = eligible.slice(i, i + BATCH_LIMIT);
    const batch = writeBatch(db);
    for (const note of chunk) {
      const payload = noteToCloudMap(note);
      batch.set(userNoteDocument(userId, note.id), payload, { merge: true });
    }
    await batch.commit();
  }

  await setDoc(userSyncMetaDocument(userId), syncMetaMap(eligible.length, 'web'), { merge: true });
  return eligible.length;
}

/**
 * Last-write-wins merge using `timestamp`, matching Android FirebaseNoteSync.
 */
export async function mergeRemoteNotes(localNotes: Note[], remoteNotes: Note[]): Promise<Note[]> {
  const byId = new Map<string, Note>(localNotes.map((note) => [note.id, note]));

  for (const remote of remoteNotes) {
    const local = byId.get(remote.id);
    if (!local) {
      byId.set(remote.id, remote);
      continue;
    }
    if (local.isLocked) continue;
    if (remote.timestamp >= local.timestamp) {
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
 * Matches Android `downloadAllNotes` conflict behavior.
 */
export async function syncNotesWithCloud(
  userId: string,
  localNotes: Note[],
): Promise<{ changes: number; merged: Note[]; remoteIds: string[] }> {
  const remoteNotes = await fetchRemoteNotes(userId);
  const cloudIds = new Set(remoteNotes.map((note) => note.id));
  let changes = 0;

  let merged = await mergeRemoteNotes(localNotes, remoteNotes);

  for (const localNote of localNotes.filter(isCloudSyncEligible)) {
    const remote = remoteNotes.find((note) => note.id === localNote.id);
    if (!cloudIds.has(localNote.id)) {
      await upsertNote(userId, localNote);
      changes++;
      continue;
    }
    if (remote && localNote.timestamp > remote.timestamp) {
      await upsertNote(userId, localNote);
      merged = merged.map((note) => (note.id === localNote.id ? localNote : note));
      changes++;
    }
  }

  for (const remoteNote of remoteNotes) {
    if (!merged.some((note) => note.id === remoteNote.id)) {
      merged.push(remoteNote);
      changes++;
    }
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
