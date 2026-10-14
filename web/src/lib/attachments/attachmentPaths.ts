import type { Attachment } from '@/types/attachment';
import type { Note } from '@/types/note';
import type { NoteAttachmentMetadata } from '@/lib/attachments/supabaseAttachmentMetadata';

export const ATTACHMENT_PENDING_PREFIX = 'pending:';
export const ATTACHMENT_R2_PREFIX = 'r2:';
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

export function createAttachmentId(): string {
  return crypto.randomUUID();
}

export function pendingStoragePath(attachmentId: string): string {
  return `${ATTACHMENT_PENDING_PREFIX}${attachmentId}`;
}

export function isPendingAttachment(storagePath: string): boolean {
  return storagePath.startsWith(ATTACHMENT_PENDING_PREFIX);
}

export function attachmentFromMetadata(
  note: Pick<Note, 'localId'>,
  row: NoteAttachmentMetadata,
): Attachment {
  return {
    id: row.attachmentId,
    noteId: note.localId,
    storagePath: `${ATTACHMENT_R2_PREFIX}${row.objectKey}`,
    type: row.attachmentType,
    mimeType: row.mimeType,
    sizeBytes: row.sizeBytes,
  };
}

export function attachmentsKey(attachments: Attachment[]): string {
  return attachments
    .map(
      (attachment) =>
        `${attachment.id}:${attachment.storagePath}:${attachment.mimeType ?? ''}:${attachment.sizeBytes ?? ''}`,
    )
    .sort()
    .join(',');
}

/**
 * [incoming] — a cloud version, whose payload cannot carry attachments — keeping [local]'s images
 * that are not uploaded yet.
 *
 * A `pending:` image exists only on this device until its upload lands, which can be long after the
 * note's words were pushed. Writing the cloud version over the local row without it would drop the
 * only reference to those bytes. Uploaded (`r2:`) images are the server's to report, through the
 * attachment listing. Kotlin's `keepingLocalOnlyAttachmentsOf` is the same rule.
 */
export function keepingLocalOnlyAttachments(incoming: Note, local: Note | undefined): Note {
  const localOnly = (local?.attachments ?? []).filter((attachment) => isPendingAttachment(attachment.storagePath));
  if (localOnly.length === 0) return incoming;
  const known = new Set(incoming.attachments.map((attachment) => attachment.id));
  const kept = localOnly.filter((attachment) => !known.has(attachment.id));
  return kept.length === 0 ? incoming : { ...incoming, attachments: [...incoming.attachments, ...kept] };
}
