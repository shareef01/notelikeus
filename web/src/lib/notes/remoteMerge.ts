import { keepingLocalOnlyAttachments } from '@/lib/attachments/attachmentPaths';
import type { Note } from '@/types/note';

/**
 * Which copy a reconcile shows and tries to push. Prefers the server-assigned `serverUpdatedAt`.
 *
 * One exception differs from Kotlin's `NoteSyncEngine` (where a confirmed side always wins): an
 * unconfirmed local note whose client `timestamp` is later than the cloud's stamp is still offered
 * for upload (NEW-03, from before the pending-push queue). It only ever decides what is *tried*:
 * the reconcile pushes such a note with no base revision, so the server refuses it against any
 * confirmed row and both versions are kept (see `unconfirmedLocalOverwrite.test.ts`).
 */
export function shouldUploadOverRemote(note: Note, remote: Note | undefined): boolean {
  if (!remote) return true;
  if (note.serverUpdatedAt != null && remote.serverUpdatedAt != null) {
    if (note.serverUpdatedAt !== remote.serverUpdatedAt) {
      return note.serverUpdatedAt > remote.serverUpdatedAt;
    }
    return note.timestamp > remote.timestamp;
  }
  if (remote.serverUpdatedAt != null) {
    if (note.serverUpdatedAt == null && note.timestamp > remote.serverUpdatedAt) {
      return true;
    }
    return false;
  }
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
      byId.set(remote.id, keepingLocalOnlyAttachments(remote, local));
    }
  }

  return Array.from(byId.values());
}
