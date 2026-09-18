import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import {
  deletePendingAttachmentForOwner,
  findPendingAttachmentForOwner,
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

/**
 * Stages a blob durably and reports whether it landed.
 *
 * The IndexedDB write is awaited rather than fired off: a note that references `pending:<id>`
 * outlives this tab, so staging that silently failed leaves the attachment as metadata pointing
 * at nothing after a reload. Callers must not reference the attachment when this returns false.
 */
export async function storePendingAttachment(
  attachmentId: string,
  blob: Blob,
  mimeType: string,
  noteId?: string,
  ownerId?: string,
): Promise<boolean> {
  const resolvedOwner = ownerId ?? resolveOwnerId();
  if (!resolvedOwner) return false;
  try {
    await putPendingAttachment({
      ownerId: resolvedOwner,
      noteId: noteId ?? '',
      attachmentId,
      blob,
      mimeType,
      sizeBytes: blob.size,
      createdAt: Date.now(),
    });
  } catch {
    return false;
  }
  pending.set(attachmentId, { blob, mimeType, noteId, ownerId: resolvedOwner });
  return true;
}

export function peekPendingAttachment(
  attachmentId: string,
  expectedOwnerId?: string,
): { blob: Blob; mimeType: string } | undefined {
  const entry = pending.get(attachmentId);
  if (!entry) return undefined;
  const owner = expectedOwnerId ?? resolveOwnerId();
  if (!owner || entry.ownerId !== owner) {
    return undefined;
  }
  return entry;
}

/**
 * Retrieves a pending attachment blob from in-memory cache or restores from IndexedDB.
 */
export async function getPendingAttachmentBlob(
  attachmentId: string,
  noteId?: string,
  ownerId?: string,
): Promise<{ blob: Blob; mimeType: string } | undefined> {
  const resolvedOwner = ownerId ?? resolveOwnerId();
  if (!resolvedOwner) return undefined;

  const inMemory = pending.get(attachmentId);
  if (inMemory) {
    if (inMemory.ownerId === resolvedOwner) {
      return inMemory;
    }
  }

  try {
    let record = noteId
      ? await getPendingAttachment(resolvedOwner, noteId, attachmentId)
      : null;
    if (!record) {
      record = await findPendingAttachmentForOwner(resolvedOwner, attachmentId);
    }
    if (record) {
      if (record.ownerId !== resolvedOwner) {
        return undefined;
      }
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

export async function releasePendingAttachment(
  attachmentId: string,
  noteId?: string,
  ownerId?: string,
): Promise<void> {
  pending.delete(attachmentId);
  const resolvedOwner = ownerId ?? resolveOwnerId();
  if (resolvedOwner) {
    try {
      await deletePendingAttachmentForOwner(resolvedOwner, attachmentId, noteId);
    } catch {
      // Best-effort IDB delete
    }
  }
}

/** Clears the in-memory pending blob store across sign-outs and account switches. */
export function clearPendingAttachmentStore(): void {
  pending.clear();
}

/** Test hook — clears the in-memory pending blob store. */
export const clearPendingAttachmentsForTests = clearPendingAttachmentStore;
