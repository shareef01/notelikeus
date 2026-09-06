import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import {
  deletePendingAttachment,
  findPendingAttachmentById,
  getPendingAttachment,
  putPendingAttachment,
} from '@/lib/local/pendingAttachmentRepository';

interface PendingEntry {
  blob: Blob;
  mimeType: string;
  noteId?: string;
  ownerId?: string | null;
}

const pending = new Map<string, PendingEntry>();

export function storePendingAttachment(
  attachmentId: string,
  blob: Blob,
  mimeType: string,
  noteId?: string,
  ownerId?: string,
): void {
  const resolvedOwner = ownerId ?? resolveOwnerId();
  pending.set(attachmentId, { blob, mimeType, noteId, ownerId: resolvedOwner });
  if (resolvedOwner) {
    void putPendingAttachment({
      ownerId: resolvedOwner,
      noteId: noteId ?? '',
      attachmentId,
      blob,
      mimeType,
      sizeBytes: blob.size,
      createdAt: Date.now(),
    }).catch(() => {});
  }
}

export function peekPendingAttachment(
  attachmentId: string,
): { blob: Blob; mimeType: string } | undefined {
  return pending.get(attachmentId);
}

/**
 * Retrieves a pending attachment blob from in-memory cache or restores from IndexedDB.
 */
export async function getPendingAttachmentBlob(
  attachmentId: string,
  noteId?: string,
  ownerId?: string,
): Promise<{ blob: Blob; mimeType: string } | undefined> {
  const inMemory = pending.get(attachmentId);
  if (inMemory) return inMemory;

  const resolvedOwner = ownerId ?? resolveOwnerId();
  try {
    const record =
      resolvedOwner && noteId
        ? await getPendingAttachment(resolvedOwner, noteId, attachmentId)
        : await findPendingAttachmentById(attachmentId);
    if (record) {
      const entry: PendingEntry = {
        blob: record.blob,
        mimeType: record.mimeType,
        noteId: record.noteId,
        ownerId: record.ownerId,
      };
      pending.set(attachmentId, entry);
      return entry;
    }
  } catch {
    // If IDB is unavailable, return undefined
  }
  return undefined;
}

/**
 * Deprecated: prefer peekPendingAttachment + releasePendingAttachment after confirmed upload
 * to prevent data loss on upload failures.
 */
export function takePendingAttachment(
  attachmentId: string,
): { blob: Blob; mimeType: string } | undefined {
  const value = pending.get(attachmentId);
  if (value) pending.delete(attachmentId);
  return value;
}

export async function releasePendingAttachment(
  attachmentId: string,
  noteId?: string,
  ownerId?: string,
): Promise<void> {
  pending.delete(attachmentId);
  const resolvedOwner = ownerId ?? resolveOwnerId();
  if (resolvedOwner && noteId) {
    try {
      await deletePendingAttachment(resolvedOwner, noteId, attachmentId);
    } catch {
      // Best-effort IDB delete
    }
  }
}

/** Test hook — clears the in-memory pending blob store. */
export function clearPendingAttachmentsForTests(): void {
  pending.clear();
}
