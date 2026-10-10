import { isPendingAttachment, ATTACHMENT_PENDING_PREFIX } from '@/lib/attachments/attachmentPaths';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { getPendingAttachmentBlob, peekPendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import type { Attachment } from '@/types/attachment';

/**
 * Small, cropped previews of a note's first image, for the note cards.
 *
 * This is deliberately not [attachmentPreviewCache]: that cache hands the editor the full-size
 * image and revokes its URL when the editor closes. A card that shared those URLs would lose its
 * picture whenever an editor opened and closed on the same note, and the reverse. Thumbnails are
 * their own cache, which nothing but this module revokes.
 *
 * A card must not hold a full-resolution decode either — a phone screenshot is 1080x2400 — so the
 * source is downscaled to a small JPEG before a URL is made for it.
 */

/** Width of the stored thumbnail; the card shows it at roughly half this, which keeps 2x screens sharp. */
const THUMB_WIDTH = 480;
/** Cards crop to 4:3, so the stored image is that shape and carries no pixels that are never shown. */
const THUMB_HEIGHT = 360;
const THUMB_QUALITY = 0.8;
/** Downloads in flight at once. A grid of image notes mounting together must not open dozens of requests. */
const MAX_CONCURRENT_LOADS = 3;
/** How long a failed load is remembered, so scrolling a broken image in and out of view does not retry each time. */
const FAILURE_MEMORY_MS = 60_000;

const thumbnailUrls = new Map<string, string>();
const inFlight = new Map<string, Promise<string | null>>();
const failedAt = new Map<string, number>();

/** Bumped by every clear, so a load that started before one cannot repopulate the cache after it. */
let generation = 0;
let activeLoads = 0;
const waiting: Array<() => void> = [];

function cacheKey(noteId: string, attachmentId: string): string {
  return `${noteId}:${attachmentId}`;
}

async function withLoadSlot<T>(work: () => Promise<T>): Promise<T> {
  if (activeLoads >= MAX_CONCURRENT_LOADS) {
    await new Promise<void>((resolve) => waiting.push(resolve));
  }
  activeLoads += 1;
  try {
    return await work();
  } finally {
    activeLoads -= 1;
    waiting.shift()?.();
  }
}

async function loadSourceBlob(noteId: string, attachment: Attachment): Promise<Blob | null> {
  if (isPendingAttachment(attachment.storagePath)) {
    const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
    const pending =
      peekPendingAttachment(pendingId) ?? (await getPendingAttachmentBlob(pendingId, noteId));
    return pending?.blob ?? null;
  }
  if (!isR2AttachmentsEnabled()) return null;
  return withLoadSlot(() => getAttachmentBlobStore().download(noteId, attachment.id));
}

/**
 * Crops to 4:3 — from the top for portrait images (screenshots read from the top down), from the
 * centre for landscape ones — and scales to the stored size. Falls back to the original bytes when
 * the browser cannot decode or encode, so a missing capability costs memory, never the picture.
 */
async function downscale(source: Blob): Promise<Blob> {
  if (typeof createImageBitmap !== 'function' || typeof document === 'undefined') return source;
  let bitmap: ImageBitmap | null = null;
  try {
    bitmap = await createImageBitmap(source);
    const { width, height } = bitmap;
    if (width === 0 || height === 0) return source;

    const targetAspect = THUMB_WIDTH / THUMB_HEIGHT;
    let sx = 0;
    let sy = 0;
    let sw = width;
    let sh = height;
    if (width / height >= targetAspect) {
      sw = Math.round(height * targetAspect);
      sx = Math.round((width - sw) / 2);
    } else {
      sh = Math.round(width / targetAspect);
    }

    const canvas = document.createElement('canvas');
    canvas.width = Math.min(THUMB_WIDTH, sw);
    canvas.height = Math.round(canvas.width / targetAspect);
    const context = canvas.getContext('2d');
    if (!context) return source;
    // JPEG has no alpha; without a backdrop a transparent PNG would come out black.
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);

    const encoded = await new Promise<Blob | null>((resolve) => {
      canvas.toBlob(resolve, 'image/jpeg', THUMB_QUALITY);
    });
    return encoded ?? source;
  } catch {
    return source;
  } finally {
    bitmap?.close();
  }
}

/** The cached thumbnail URL for an attachment, if one has already been made. */
export function peekAttachmentThumbnailUrl(noteId: string, attachmentId: string): string | null {
  return thumbnailUrls.get(cacheKey(noteId, attachmentId)) ?? null;
}

export function resolveAttachmentThumbnailUrl(
  noteId: string,
  attachment: Attachment,
): Promise<string | null> {
  const key = cacheKey(noteId, attachment.id);
  const cached = thumbnailUrls.get(key);
  if (cached) return Promise.resolve(cached);

  const failed = failedAt.get(key);
  if (failed !== undefined && Date.now() - failed < FAILURE_MEMORY_MS) return Promise.resolve(null);

  const running = inFlight.get(key);
  if (running) return running;

  const startedIn = generation;
  const task = (async () => {
    try {
      const source = await loadSourceBlob(noteId, attachment);
      if (!source) {
        failedAt.set(key, Date.now());
        return null;
      }
      const thumbnail = await downscale(source);
      // The session's data was cleared while this was loading: the picture belongs to an account
      // that is no longer signed in, so it is dropped rather than cached.
      if (startedIn !== generation) return null;
      const url = URL.createObjectURL(thumbnail);
      thumbnailUrls.set(key, url);
      failedAt.delete(key);
      return url;
    } catch {
      failedAt.set(key, Date.now());
      return null;
    } finally {
      inFlight.delete(key);
    }
  })();
  inFlight.set(key, task);
  return task;
}

/**
 * Drops every thumbnail. Called when the session's data is cleared (sign-out, account switch) so one
 * account's pictures are not kept in memory for the next, and by tests.
 */
export function clearAttachmentThumbnailCache(): void {
  generation += 1;
  for (const url of thumbnailUrls.values()) URL.revokeObjectURL(url);
  thumbnailUrls.clear();
  failedAt.clear();
}
