import { SuspiciousEmptySnapshotError } from '@/lib/remote/remoteErrors';
import { shouldUploadOverRemote } from '@/lib/notes/remoteMerge';
import type { Note } from '@/types/note';

export interface ReconcileSnapshotInput {
  localNotes: Note[];
  remoteNotes: Note[];
  remoteTombstones: Record<string, number>;
  /** Cloud ids from the last successful complete snapshot (memory and/or IndexedDB meta). */
  knownRemoteIds: Set<string>;
  isDeleted: (id: string) => boolean;
}

export interface ReconcileSnapshotResult {
  merged: Note[];
  toUpload: Note[];
  newlyDeletedIds: string[];
  nextKnownRemoteIds: Set<string>;
}

function wasPreviouslyRemote(note: Note, knownRemoteIds: Set<string>): boolean {
  return knownRemoteIds.has(note.id) || note.serverUpdatedAt != null;
}

/**
 * Pure merge of a complete remote snapshot with the durable local library.
 *
 * Cloud tombstones and known-remote ids that are absent from a non-empty snapshot delete
 * locally. Local-only unsynced notes (never confirmed remotely) survive a non-empty snapshot
 * that does not mention them. An unexplained empty snapshot throws
 * {@link SuspiciousEmptySnapshotError} instead of mass-deleting.
 */
export function reconcileLocalAndRemoteSnapshot(
  input: ReconcileSnapshotInput,
): ReconcileSnapshotResult {
  const { localNotes, remoteNotes, remoteTombstones, knownRemoteIds, isDeleted } = input;
  const tombstoneIds = new Set(Object.keys(remoteTombstones));
  const remoteById = new Map<string, Note>();
  for (const note of remoteNotes) {
    if (tombstoneIds.has(note.id) || isDeleted(note.id)) continue;
    remoteById.set(note.id, note);
  }

  const unexplainedMissing: string[] = [];
  for (const local of localNotes) {
    if (isDeleted(local.id) || tombstoneIds.has(local.id)) continue;
    if (!wasPreviouslyRemote(local, knownRemoteIds)) continue;
    if (remoteById.has(local.id)) continue;
    unexplainedMissing.push(local.id);
  }

  if (remoteNotes.length === 0 && unexplainedMissing.length > 0) {
    throw new SuspiciousEmptySnapshotError(unexplainedMissing.length);
  }

  const newlyDeletedIds: string[] = [];
  const markDeleted = (id: string) => {
    if (!newlyDeletedIds.includes(id)) newlyDeletedIds.push(id);
  };

  for (const id of tombstoneIds) {
    markDeleted(id);
  }

  if (remoteNotes.length > 0) {
    for (const id of knownRemoteIds) {
      if (!remoteById.has(id) && !tombstoneIds.has(id)) {
        markDeleted(id);
      }
    }
    for (const local of localNotes) {
      if (!wasPreviouslyRemote(local, knownRemoteIds)) continue;
      if (!remoteById.has(local.id) && !tombstoneIds.has(local.id)) {
        markDeleted(local.id);
      }
    }
  }

  const deleted = new Set<string>();
  for (const id of newlyDeletedIds) deleted.add(id);
  const isGone = (id: string) => isDeleted(id) || deleted.has(id) || tombstoneIds.has(id);

  const mergedById = new Map<string, Note>();

  for (const remote of remoteById.values()) {
    if (isGone(remote.id)) continue;
    const local = localNotes.find((note) => note.id === remote.id);
    if (local && !isGone(local.id) && shouldUploadOverRemote(local, remote)) {
      mergedById.set(local.id, local);
    } else {
      mergedById.set(remote.id, remote);
    }
  }

  const toUpload: Note[] = [];
  for (const local of localNotes) {
    if (isGone(local.id)) continue;
    const remote = remoteById.get(local.id);
    if (remote) {
      if (shouldUploadOverRemote(local, remote)) {
        mergedById.set(local.id, local);
        toUpload.push(local);
      }
      continue;
    }
    // Local-only unsynced (import, guest→sign-in, offline create). Keep and upload later.
    mergedById.set(local.id, local);
    toUpload.push(local);
  }

  const nextKnownRemoteIds = new Set(remoteById.keys());

  return {
    merged: Array.from(mergedById.values()),
    toUpload,
    newlyDeletedIds,
    nextKnownRemoteIds,
  };
}
