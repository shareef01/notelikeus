import type { AttachmentBlobStore } from '@/lib/attachments/attachmentBlobStore';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { noopAttachmentBlobStore } from '@/lib/attachments/noopAttachmentBlobStore';
import { createR2AttachmentBlobStore } from '@/lib/attachments/r2AttachmentBlobStore';

let activeOverride: AttachmentBlobStore | null = null;

export function getAttachmentBlobStore(): AttachmentBlobStore {
  if (activeOverride) return activeOverride;
  return isR2AttachmentsEnabled() ? createR2AttachmentBlobStore() : noopAttachmentBlobStore;
}

export function setAttachmentBlobStoreForTests(store: AttachmentBlobStore): void {
  activeOverride = store;
}

export function resetAttachmentBlobStoreForTests(): void {
  activeOverride = null;
}
