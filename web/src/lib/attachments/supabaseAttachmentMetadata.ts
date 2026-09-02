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
  return (data ?? []) as NoteAttachmentMetadata[];
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
