import { noteSyncPayloadEqual } from '@/lib/notes/noteEquality';
import type { Note } from '@/types/note';

/**
 * Whether a local mutation should be sent given the current remote row.
 *
 * Server revision (`serverUpdatedAt` as the confirmed-revision marker) is authoritative.
 * The client wall clock never decides a winner when both sides share a confirmed revision:
 * a local edit based on revision R may upload even if the device clock is behind the cloud
 * copy's `timestamp`. Identical payloads are skipped so unchanged notes are not pushed on
 * every sync.
 *
 * Unconfirmed local notes (imports, first write) never overwrite a confirmed remote row.
 * That decision still belongs to the RPC once a base revision exists.
 */
export function shouldUploadOverRemote(note: Note, remote: Note | undefined): boolean {
  if (!remote) return true;
  if (note.serverUpdatedAt != null && remote.serverUpdatedAt != null) {
    if (note.serverUpdatedAt !== remote.serverUpdatedAt) {
      return note.serverUpdatedAt > remote.serverUpdatedAt;
    }
    return !noteSyncPayloadEqual(note, remote);
  }
  if (remote.serverUpdatedAt != null) return false;
  if (note.serverUpdatedAt != null) return true;
  return note.timestamp >= remote.timestamp;
}

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
