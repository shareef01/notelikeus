import { deleteDoc, getDocs, setDoc } from 'firebase/firestore';
import {
  userTombstoneDocument,
  userTombstonesCollection,
} from '@/lib/firestore/paths';

/** Keep cloud tombstones long enough for offline devices to observe the delete. */
export const TOMBSTONE_TTL_MS = 30 * 24 * 60 * 60 * 1000;

export async function writeCloudTombstone(
  userId: string,
  noteId: string,
  deletedAt = Date.now(),
): Promise<void> {
  await setDoc(userTombstoneDocument(userId, noteId), { deletedAt }, { merge: true });
}

export async function fetchCloudTombstones(
  userId: string,
): Promise<Record<string, number>> {
  const snapshot = await getDocs(userTombstonesCollection(userId));
  const result: Record<string, number> = {};
  for (const docSnap of snapshot.docs) {
    const deletedAt = docSnap.data()?.deletedAt;
    result[docSnap.id] =
      typeof deletedAt === 'number' && Number.isFinite(deletedAt) ? deletedAt : Date.now();
  }
  return result;
}

export async function deleteCloudTombstone(userId: string, noteId: string): Promise<void> {
  await deleteDoc(userTombstoneDocument(userId, noteId));
}

/** Drop expired tombstones that are no longer needed to suppress note resurrection. */
export async function pruneExpiredCloudTombstones(
  userId: string,
  tombstones: Record<string, number>,
  liveNoteIds: Set<string>,
  now = Date.now(),
): Promise<string[]> {
  const pruned: string[] = [];
  for (const [noteId, deletedAt] of Object.entries(tombstones)) {
    if (liveNoteIds.has(noteId)) continue;
    if (now - deletedAt < TOMBSTONE_TTL_MS) continue;
    await deleteCloudTombstone(userId, noteId);
    pruned.push(noteId);
  }
  return pruned;
}
