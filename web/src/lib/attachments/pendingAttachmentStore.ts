const pending = new Map<string, { blob: Blob; mimeType: string }>();

export function storePendingAttachment(
  attachmentId: string,
  blob: Blob,
  mimeType: string,
): void {
  pending.set(attachmentId, { blob, mimeType });
}

export function peekPendingAttachment(
  attachmentId: string,
): { blob: Blob; mimeType: string } | undefined {
  return pending.get(attachmentId);
}

export function takePendingAttachment(
  attachmentId: string,
): { blob: Blob; mimeType: string } | undefined {
  const value = pending.get(attachmentId);
  if (value) pending.delete(attachmentId);
  return value;
}

export function releasePendingAttachment(attachmentId: string): void {
  pending.delete(attachmentId);
}

/** Test hook — clears the in-memory pending blob store. */
export function clearPendingAttachmentsForTests(): void {
  pending.clear();
}
