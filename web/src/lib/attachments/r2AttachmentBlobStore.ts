import {
  attachmentWorkerPath,
  buildAttachmentObjectKey,
} from '@/lib/attachments/attachmentObjectKey';
import type {
  AttachmentBlobStore,
  AttachmentBlobUploadResult,
} from '@/lib/attachments/attachmentBlobStore';
import { loadAttachmentsWorkerUrl } from '@/lib/attachments/attachmentConfig';
import { registerNoteAttachment, deleteNoteAttachment } from '@/lib/attachments/supabaseAttachmentMetadata';
import { getSupabaseClient } from '@/lib/supabase/client';

export function createR2AttachmentBlobStore(
  workerBaseUrl: string = loadAttachmentsWorkerUrl(),
): AttachmentBlobStore {
  const base = workerBaseUrl.replace(/\/$/, '');

  async function accessToken(): Promise<string> {
    const { data, error } = await getSupabaseClient().auth.getSession();
    const token = data.session?.access_token?.trim();
    if (error || !token) {
      throw new Error('missing Supabase session for attachment upload');
    }
    return token;
  }

  async function ownerId(): Promise<string> {
    const { data, error } = await getSupabaseClient().auth.getUser();
    const id = data.user?.id?.trim();
    if (error || !id) {
      throw new Error('missing Supabase user for attachment storage');
    }
    return id;
  }

  return {
    async upload(noteId, attachmentId, blob, mimeType): Promise<AttachmentBlobUploadResult> {
      const token = await accessToken();
      const userId = await ownerId();
      const objectKey = buildAttachmentObjectKey(userId, noteId, attachmentId);
      const response = await fetch(`${base}${attachmentWorkerPath(noteId, attachmentId)}`, {
        method: 'PUT',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': mimeType,
        },
        body: blob,
      });
      if (!response.ok) {
        throw new Error(`attachment upload failed (${response.status})`);
      }
      const payload = (await response.json()) as {
        objectKey?: string;
        sizeBytes?: number;
        mimeType?: string;
      };
      await registerNoteAttachment({
        attachmentId,
        noteId,
        objectKey: payload.objectKey ?? objectKey,
        mimeType: payload.mimeType ?? mimeType,
        sizeBytes: payload.sizeBytes ?? blob.size,
      });
      return {
        objectKey: payload.objectKey ?? objectKey,
        sizeBytes: payload.sizeBytes ?? blob.size,
        mimeType: payload.mimeType ?? mimeType,
      };
    },

    async download(noteId, attachmentId): Promise<Blob> {
      const token = await accessToken();
      const response = await fetch(`${base}${attachmentWorkerPath(noteId, attachmentId)}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!response.ok) {
        throw new Error(`attachment download failed (${response.status})`);
      }
      return response.blob();
    },

    async delete(noteId, attachmentId): Promise<void> {
      const token = await accessToken();
      const response = await fetch(`${base}${attachmentWorkerPath(noteId, attachmentId)}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!response.ok) {
        throw new Error(`attachment delete failed (${response.status})`);
      }
      await deleteNoteAttachment(attachmentId, noteId);
    },
  };
}
