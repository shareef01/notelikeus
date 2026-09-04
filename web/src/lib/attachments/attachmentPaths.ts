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

export function isR2Attachment(storagePath: string): boolean {
  return storagePath.startsWith(ATTACHMENT_R2_PREFIX);
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

export function firstImageAttachment(attachments: Attachment[]): Attachment | undefined {
  return attachments.find((attachment) => {
    const mime = attachment.mimeType?.toLowerCase() ?? '';
    return attachment.type === 'image' || mime.startsWith('image/');
  });
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
