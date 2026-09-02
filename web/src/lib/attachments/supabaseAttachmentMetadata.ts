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

export async function registerNoteAttachment(input: {
  attachmentId: string;
  noteId: string;
  objectKey: string;
  mimeType: string;
  sizeBytes: number;
  attachmentType?: string;
}): Promise<void> {
  const { error } = await getSupabaseClient().rpc('register_note_attachment', {
    p_attachment_id: input.attachmentId,
    p_note_id: input.noteId,
    p_object_key: input.objectKey,
    p_mime_type: input.mimeType,
    p_size_bytes: input.sizeBytes,
    p_attachment_type: input.attachmentType ?? 'image',
  });
  if (error) {
    throw error;
  }
}

export async function listNoteAttachments(noteId: string): Promise<NoteAttachmentMetadata[]> {
  const { data, error } = await getSupabaseClient().rpc('list_note_attachments', {
    p_note_id: noteId,
  });
  if (error) {
    throw error;
  }
  return parseAttachmentMetadataList(data);
}

export async function listUserAttachments(): Promise<NoteAttachmentMetadata[]> {
  const { data, error } = await getSupabaseClient().rpc('list_user_attachments');
  if (error) {
    throw error;
  }
  return parseAttachmentMetadataList(data);
}

export async function deleteNoteAttachment(
  attachmentId: string,
  noteId: string,
): Promise<void> {
  const { error } = await getSupabaseClient().rpc('delete_note_attachment', {
    p_attachment_id: attachmentId,
    p_note_id: noteId,
  });
  if (error) {
    throw error;
  }
}
