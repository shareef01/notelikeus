import { isPendingAttachment, ATTACHMENT_PENDING_PREFIX } from '@/lib/attachments/attachmentPaths';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { getPendingAttachmentBlob, peekPendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import { createImageObjectUrl } from '@/lib/attachments/imageObjectUrl';
import type { Attachment } from '@/types/attachment';

/**
 * Full-size pictures for the editor, kept after the editor closes so reopening a note shows them at
 * once instead of downloading them again.
 *
 * Bounded, newest-used last. An entry an open editor is showing is retained and never evicted, so
 * its URL cannot be revoked out from under it. [clearAttachmentPreviewCache] drops everything when
 * the session's data is cleared, as the card thumbnails are.
 */

/** Few entries: each is a full-size image, far larger than a card thumbnail. */
const MAX_CACHED_PREVIEWS = 12;

const previewUrls = new Map<string, string>();
const inFlight = new Map<string, Promise<string | null>>();
const activeRefs = new Map<string, number>();

/** Bumped by every clear, so a load that started before one cannot repopulate the cache after it. */
let generation = 0;

function cacheKey(noteId: string, attachmentId: string): string {
  return `${noteId}:${attachmentId}`;
}

/** Keeps a preview's URL alive while an editor shows it. Pair with [releaseAttachmentPreview]. */
export function retainAttachmentPreview(noteId: string, attachmentId: string): void {
  const key = cacheKey(noteId, attachmentId);
  activeRefs.set(key, (activeRefs.get(key) ?? 0) + 1);
}

export function releaseAttachmentPreview(noteId: string, attachmentId: string): void {
  const key = cacheKey(noteId, attachmentId);
  const current = (activeRefs.get(key) ?? 0) - 1;
  if (current <= 0) {
    activeRefs.delete(key);
  } else {
    activeRefs.set(key, current);
  }
  evictUnreferencedLru(MAX_CACHED_PREVIEWS);
}

function evictUnreferencedLru(targetSize: number): void {
  while (previewUrls.size > targetSize) {
    let evictedKey: string | null = null;
    for (const candidateKey of previewUrls.keys()) {
      if ((activeRefs.get(candidateKey) ?? 0) === 0) {
        evictedKey = candidateKey;
        break;
      }
    }
    // Everything left is on screen; keep it rather than break a picture that is showing.
    if (!evictedKey) break;
    const url = previewUrls.get(evictedKey);
    if (url) URL.revokeObjectURL(url);
    previewUrls.delete(evictedKey);
  }
}

/** The cached preview URL for an attachment, if one has already been made. */
export function peekAttachmentPreviewUrl(noteId: string, attachmentId: string): string | null {
  const key = cacheKey(noteId, attachmentId);
  const url = previewUrls.get(key);
  if (!url) return null;
  // Re-insert: now the most recently used.
  previewUrls.delete(key);
  previewUrls.set(key, url);
  return url;
}

async function loadSourceBlob(noteId: string, attachment: Attachment): Promise<Blob | null> {
  if (isPendingAttachment(attachment.storagePath)) {
    const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
    const pending =
      peekPendingAttachment(pendingId) ?? (await getPendingAttachmentBlob(pendingId, noteId));
    return pending?.blob ?? null;
  }
  if (!isR2AttachmentsEnabled()) return null;
  return getAttachmentBlobStore().download(noteId, attachment.id);
}

export function resolveAttachmentPreviewUrl(
  noteId: string,
  attachment: Attachment,
): Promise<string | null> {
  const cached = peekAttachmentPreviewUrl(noteId, attachment.id);
  if (cached) return Promise.resolve(cached);

  // Two editors (or React's double-run effects) asking at once share one download and one URL.
  const key = cacheKey(noteId, attachment.id);
  const running = inFlight.get(key);
  if (running) return running;

  const startedIn = generation;
  const task = (async () => {
    try {
      const blob = await loadSourceBlob(noteId, attachment);
      // Cleared while loading: the picture belongs to a session that has ended, so it is not kept.
      if (!blob || startedIn !== generation) return null;
      evictUnreferencedLru(MAX_CACHED_PREVIEWS - 1);
      const url = createImageObjectUrl(blob);
      previewUrls.set(key, url);
      return url;
    } catch {
      return null;
    } finally {
      inFlight.delete(key);
    }
  })();
  inFlight.set(key, task);
  return task;
}

/** Forgets one attachment's preview, when the attachment itself is removed from the note. */
export function revokeAttachmentPreviewUrl(noteId: string, attachmentId: string): void {
  const key = cacheKey(noteId, attachmentId);
  const url = previewUrls.get(key);
  if (url) {
    URL.revokeObjectURL(url);
    previewUrls.delete(key);
  }
}

/**
 * Drops every preview. Called when the session's data is cleared (sign-out, account switch) so one
 * account's pictures are not kept in memory for the next, and by tests.
 */
export function clearAttachmentPreviewCache(): void {
  generation += 1;
  for (const url of previewUrls.values()) URL.revokeObjectURL(url);
  previewUrls.clear();
  activeRefs.clear();
}

/** Test hook — revokes and clears all preview URLs. */
export const clearAttachmentPreviewCacheForTests = clearAttachmentPreviewCache;
