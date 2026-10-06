const NOTE_ID_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;
const ATTACHMENT_ID_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;

export function buildAttachmentObjectKey(
  ownerId: string,
  noteId: string,
  attachmentId: string,
): string {
  const trimmedNoteId = noteId.trim();
  const trimmedAttachmentId = attachmentId.trim();
  if (!NOTE_ID_PATTERN.test(trimmedNoteId) || !ATTACHMENT_ID_PATTERN.test(trimmedAttachmentId)) {
    throw new Error('invalid attachment path segment');
  }
  return `owners/${ownerId}/notes/${trimmedNoteId}/${trimmedAttachmentId}`;
}

/**
 * An upload route, with the commitment protocol it asks for.
 *
 * `v1` is the protocol every deployed client and Worker already speak: an upload's metadata row is
 * committed the moment it is stored. `v2` asks for the deferred protocol, where the row stays
 * provisional until the note that references it commits.
 *
 * The version is part of the path on purpose. A Worker that predates the deferred protocol has no `v2`
 * route at all, so it answers 404 *before it reads a byte of the body, writes an object, or calls the
 * database* -- which is what makes it safe for a new client to be deployed ahead of a Worker. A header
 * would not: an old Worker ignores an unknown header and commits the row anyway, which is exactly the
 * orphan this protocol exists to prevent.
 */
export function parseAttachmentPath(
  pathname: string,
): { noteId: string; attachmentId: string; deferred: boolean } | null {
  const match = pathname.match(/^\/(v1|v2)\/attachments\/([^/]+)\/([^/]+)$/);
  if (!match) return null;
  const deferred = match[1] === 'v2';
  let noteId: string;
  let attachmentId: string;
  try {
    noteId = decodeURIComponent(match[2]);
    attachmentId = decodeURIComponent(match[3]);
  } catch {
    return null;
  }
  if (!NOTE_ID_PATTERN.test(noteId) || !ATTACHMENT_ID_PATTERN.test(attachmentId)) {
    return null;
  }
  return { noteId, attachmentId, deferred };
}

export function isAttachmentObjectKeyForOwner(objectKey: string, ownerId: string): boolean {
  const prefix = `owners/${ownerId}/notes/`;
  if (!objectKey.startsWith(prefix)) return false;
  const rest = objectKey.slice(prefix.length);
  const slash = rest.indexOf('/');
  if (slash <= 0 || slash === rest.length - 1) return false;
  const noteId = rest.slice(0, slash);
  const attachmentId = rest.slice(slash + 1);
  return NOTE_ID_PATTERN.test(noteId) && ATTACHMENT_ID_PATTERN.test(attachmentId);
}
