import type { Note } from '@/types/note';

/**
 * Last-write-wins merge helper shared by the Supabase remote adapter.
 * Prefers server-assigned `serverUpdatedAt`; a confirmed remote beats an unconfirmed local
 * (e.g. backup import) regardless of client `timestamp`.
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
