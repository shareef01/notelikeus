import type { AttachmentBlobStore } from '@/lib/attachments/attachmentBlobStore';

export const noopAttachmentBlobStore: AttachmentBlobStore = {
  async upload() {
    throw new Error('Attachment storage is not enabled');
  },
  async download() {
    throw new Error('Attachment storage is not enabled');
  },
  async delete() {
    throw new Error('Attachment storage is not enabled');
  },
};
