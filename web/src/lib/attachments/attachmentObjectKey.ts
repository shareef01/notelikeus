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

export function attachmentWorkerPath(noteId: string, attachmentId: string): string {
  return `/v1/attachments/${encodeURIComponent(noteId.trim())}/${encodeURIComponent(attachmentId.trim())}`;
}
