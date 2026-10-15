import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import {
  ATTACHMENT_PENDING_PREFIX,
  ATTACHMENT_R2_PREFIX,
  createAttachmentId,
  isPendingAttachment,
  pendingStoragePath,
} from '@/lib/attachments/attachmentPaths';
import {
  getPendingAttachmentBlob,
  releasePendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { listUserAttachments } from '@/lib/attachments/supabaseAttachmentMetadata';
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

/**
 * [local] with each upload no commit has named yet turned back into a `pending:` image staged on
 * this device, so {@link withPendingAttachments} can carry it into a conflicted copy.
 *
 * Uploads use the deferred protocol: a row stays provisional, and out of every listing, until a
 * note commit names it. A push that conflicted never committed, so an image it uploaded is visible
 * nowhere — the cloud version will not show it and the copy cannot adopt a row keyed to another
 * note — and would be swept. Its bytes are still readable, so they are fetched back. Committed
 * uploads are left alone: the original note keeps them. Throws when the listing or a fetch fails,
 * so the caller leaves the conflict for a later flush; when the cloud deleted the note
 * ([noteGone]), its rows are deleted with it and an unreadable one is skipped.
 */
export async function reclaimUncommittedUploads(
  local: Note,
  ownerId: string,
  { noteGone }: { noteGone: boolean },
): Promise<Note> {
  const uploaded = local.attachments.filter((attachment) => attachment.storagePath.startsWith(ATTACHMENT_R2_PREFIX));
  if (uploaded.length === 0 || !isR2AttachmentsEnabled()) return local;
  const committed = new Set(
    (await listUserAttachments()).filter((row) => row.noteId === local.id).map((row) => row.attachmentId),
  );
  const store = getAttachmentBlobStore();
  const attachments: Attachment[] = [];
  for (const attachment of local.attachments) {
    if (!uploaded.includes(attachment) || committed.has(attachment.id)) {
      attachments.push(attachment);
      continue;
    }
    let blob: Blob;
    try {
      blob = await store.download(local.id, attachment.id);
    } catch (error) {
      if (noteGone) continue;
      throw error;
    }
    const mimeType = attachment.mimeType ?? (blob.type || 'image/jpeg');
    if (!(await storePendingAttachment(attachment.id, blob, mimeType, local.id, ownerId))) {
      throw new Error(`Could not stage attachment ${attachment.id} for its conflicted copy`);
    }
    attachments.push({ ...attachment, storagePath: pendingStoragePath(attachment.id), mimeType });
  }
  return { ...local, attachments };
}

/** Undoes {@link withPendingAttachments} for a copy that was never written. */
export async function releaseCopyAttachments(copy: Note, ownerId?: string): Promise<void> {
  await releaseCopiedPendingAttachments(copy, null, ownerId);
}
