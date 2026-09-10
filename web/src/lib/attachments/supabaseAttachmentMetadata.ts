import { getSupabaseClient } from '@/lib/supabase/client';

export interface NoteAttachmentMetadata {
  attachmentId: string;
  noteId: string;
  objectKey: string;
  mimeType: string;
  sizeBytes: number;
  attachmentType: string;
  createdAt: number;
}

function parseAttachmentMetadata(row: Record<string, unknown>): NoteAttachmentMetadata {
  return {
    attachmentId: String(row.attachment_id ?? ''),
    noteId: String(row.note_id ?? ''),
    objectKey: String(row.object_key ?? ''),
    mimeType: String(row.mime_type ?? 'application/octet-stream'),
    sizeBytes: Number(row.size_bytes ?? 0),
    attachmentType: String(row.attachment_type ?? 'image'),
    createdAt: Number(row.created_at ?? 0),
  };
}

function parseAttachmentMetadataList(data: unknown): NoteAttachmentMetadata[] {
  if (!Array.isArray(data)) return [];
  return data
    .map((row) => parseAttachmentMetadata((row ?? {}) as Record<string, unknown>))
    .filter((row) => row.attachmentId.length > 0 && row.noteId.length > 0);
}

export async function listUserAttachments(): Promise<NoteAttachmentMetadata[]> {
  const { data, error } = await getSupabaseClient().rpc('list_user_attachments');
  if (error) {
    throw error;
  }
  return parseAttachmentMetadataList(data);
}

export interface PendingDeletedAttachment {
  attachmentId: string;
  noteId: string;
  objectKey: string;
}

export async function listPendingDeletedAttachments(): Promise<PendingDeletedAttachment[]> {
  const { data, error } = await getSupabaseClient().rpc('list_pending_deleted_attachments');
  if (error) throw error;
  if (!Array.isArray(data)) return [];
  return data
    .map((row) => {
      const rec = (row ?? {}) as Record<string, unknown>;
      return {
        attachmentId: String(rec.attachment_id ?? ''),
        noteId: String(rec.note_id ?? ''),
        objectKey: String(rec.object_key ?? ''),
      };
    })
    .filter((row) => row.attachmentId.length > 0 && row.noteId.length > 0);
}

export async function purgeDeletedNoteAttachment(
  attachmentId: string,
  noteId: string,
): Promise<void> {
  const { error } = await getSupabaseClient().rpc('purge_deleted_note_attachment', {
    p_attachment_id: attachmentId,
    p_note_id: noteId,
  });
  if (error) throw error;
}
