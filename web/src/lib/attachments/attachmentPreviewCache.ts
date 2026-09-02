import { isPendingAttachment, ATTACHMENT_PENDING_PREFIX } from '@/lib/attachments/attachmentPaths';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { peekPendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import type { Attachment } from '@/types/attachment';

const previewUrls = new Map<string, string>();

function cacheKey(noteId: string, attachmentId: string): string {
  return `${noteId}:${attachmentId}`;
}

export async function resolveAttachmentPreviewUrl(
  noteId: string,
  attachment: Attachment,
): Promise<string | null> {
  const key = cacheKey(noteId, attachment.id);
  const cached = previewUrls.get(key);
  if (cached) return cached;

  if (isPendingAttachment(attachment.storagePath)) {
    const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
    const pending = peekPendingAttachment(pendingId);
    if (!pending) return null;
    const url = URL.createObjectURL(pending.blob);
    previewUrls.set(key, url);
    return url;
  }

  if (!isR2AttachmentsEnabled()) return null;

  try {
    const blob = await getAttachmentBlobStore().download(noteId, attachment.id);
    const url = URL.createObjectURL(blob);
    previewUrls.set(key, url);
    return url;
  } catch {
    return null;
  }
}

export function revokeAttachmentPreviewUrl(noteId: string, attachmentId: string): void {
  const key = cacheKey(noteId, attachmentId);
  const url = previewUrls.get(key);
  if (url) {
    URL.revokeObjectURL(url);
    previewUrls.delete(key);
  }
}

export function revokeAttachmentPreviewUrlsForNote(noteId: string): void {
  for (const [key, url] of previewUrls.entries()) {
    if (!key.startsWith(`${noteId}:`)) continue;
    URL.revokeObjectURL(url);
    previewUrls.delete(key);
  }
}

/** Test hook — revokes and clears all preview URLs. */
export function clearAttachmentPreviewCacheForTests(): void {
  for (const url of previewUrls.values()) {
    URL.revokeObjectURL(url);
  }
  previewUrls.clear();
}
