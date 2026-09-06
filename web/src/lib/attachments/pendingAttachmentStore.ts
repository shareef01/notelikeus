import {
  bindPendingAttachmentNoteId,
  deletePendingAttachment,
  getPendingAttachment,
  putPendingAttachment,
} from '@/lib/local/pendingAttachmentRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';

const pending = new Map<string, { blob: Blob; mimeType: string }>();

export async function storePendingAttachment(
  attachmentId: string,
  blob: Blob,
  mimeType: string,
  noteId: string | null = null,
): Promise<void> {
  pending.set(attachmentId, { blob, mimeType });
  const ownerId = resolveOwnerId();
  if (!ownerId) return;
  await putPendingAttachment(ownerId, attachmentId, blob, mimeType, noteId);
}

export function peekPendingAttachment(
  attachmentId: string,
): { blob: Blob; mimeType: string } | undefined {
  return pending.get(attachmentId);
}

export async function loadPendingAttachment(
  attachmentId: string,
): Promise<{ blob: Blob; mimeType: string } | undefined> {
  const cached = pending.get(attachmentId);
  if (cached) return cached;
  const ownerId = resolveOwnerId();
  if (!ownerId) return undefined;
  const record = await getPendingAttachment(ownerId, attachmentId);
  if (!record) return undefined;
  const value = { blob: record.blob, mimeType: record.mimeType };
  pending.set(attachmentId, value);
  return value;
}

/** Peek only — never consume the blob until upload succeeds. */
export function takePendingAttachment(
  attachmentId: string,
): { blob: Blob; mimeType: string } | undefined {
  return peekPendingAttachment(attachmentId);
}

export async function releasePendingAttachment(attachmentId: string): Promise<void> {
  pending.delete(attachmentId);
  const ownerId = resolveOwnerId();
  if (!ownerId) return;
  await deletePendingAttachment(ownerId, attachmentId);
}

export async function markPendingAttachmentUploaded(attachmentId: string): Promise<void> {
  await releasePendingAttachment(attachmentId);
}

export async function rebindPendingAttachmentNote(
  attachmentId: string,
  noteId: string,
): Promise<void> {
  const ownerId = resolveOwnerId();
  if (!ownerId) return;
  await bindPendingAttachmentNoteId(ownerId, attachmentId, noteId);
}

/** Test hook — clears the in-memory pending blob cache. IndexedDB is reset separately. */
export function clearPendingAttachmentsForTests(): void {
  pending.clear();
}
