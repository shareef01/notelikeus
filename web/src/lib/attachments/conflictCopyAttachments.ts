import {
  ATTACHMENT_PENDING_PREFIX,
  createAttachmentId,
  isPendingAttachment,
  pendingStoragePath,
} from '@/lib/attachments/attachmentPaths';
import {
  getPendingAttachmentBlob,
  releasePendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import type { Attachment } from '@/types/attachment';
import type { Note } from '@/types/note';

function pendingIdOf(attachment: Attachment): string {
  return attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
}

/**
 * [copy] carrying the images of [source] that exist only on this device.
 *
 * A pending image's bytes live nowhere but this device's IndexedDB, keyed to the note that staged
 * them, so a conflicted copy that dropped them would drop the only copy. Each one is staged again
 * under the copy with a new attachment id (the cloud keys attachments by id per owner, so two
 * notes cannot share one) before the copy is written; an image whose staging fails is left out
 * rather than referenced without bytes. Uploaded images are not copied: they are in the cloud
 * already, under the original note, which the conflict keeps.
 */
export async function withPendingAttachments(
  source: Note,
  copy: Note,
  ownerId?: string,
): Promise<Note> {
  const attachments: Attachment[] = [];
  for (const attachment of source.attachments) {
    if (!isPendingAttachment(attachment.storagePath)) continue;
    const pending = await getPendingAttachmentBlob(pendingIdOf(attachment), source.id, ownerId);
    if (!pending) continue;
    const id = createAttachmentId();
    if (!(await storePendingAttachment(id, pending.blob, pending.mimeType, copy.id, ownerId))) continue;
    attachments.push({ ...attachment, id, noteId: copy.localId, storagePath: pendingStoragePath(id) });
  }
  return { ...copy, attachments };
}

/**
 * Drops the staged bytes [source] no longer needs once its copy holds its own: those of pending
 * images that [kept] (what remains under the source's id, or null when nothing does) does not
 * reference. Best effort; a leftover record is only unused space.
 */
export async function releaseCopiedPendingAttachments(
  source: Note,
  kept: Note | null,
  ownerId?: string,
): Promise<void> {
  const stillUsed = new Set((kept?.attachments ?? []).map((attachment) => attachment.storagePath));
  await Promise.all(
    source.attachments
      .filter((attachment) => isPendingAttachment(attachment.storagePath) && !stillUsed.has(attachment.storagePath))
      .map((attachment) => releasePendingAttachment(pendingIdOf(attachment), source.id, ownerId).catch(() => {})),
  );
}

/** Undoes {@link withPendingAttachments} for a copy that was never written. */
export async function releaseCopyAttachments(copy: Note, ownerId?: string): Promise<void> {
  await releaseCopiedPendingAttachments(copy, null, ownerId);
}
