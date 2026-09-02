import {
  attachmentFromMetadata,
  ATTACHMENT_PENDING_PREFIX,
  ATTACHMENT_R2_PREFIX,
  isPendingAttachment,
} from '@/lib/attachments/attachmentPaths';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import {
  listUserAttachments,
  type NoteAttachmentMetadata,
} from '@/lib/attachments/supabaseAttachmentMetadata';
import { takePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import type { Attachment } from '@/types/attachment';
import type { Note } from '@/types/note';

export function mergeAttachmentsIntoNotes(
  notes: Note[],
  metadata: NoteAttachmentMetadata[],
): Note[] {
  const byNoteId = new Map<string, NoteAttachmentMetadata[]>();
  for (const row of metadata) {
    const list = byNoteId.get(row.noteId) ?? [];
    list.push(row);
    byNoteId.set(row.noteId, list);
  }

  return notes.map((note) => {
    const remote = (byNoteId.get(note.id) ?? []).map((row) =>
      attachmentFromMetadata(note, row),
    );
    const pending = note.attachments.filter((attachment) =>
      isPendingAttachment(attachment.storagePath),
    );
    const remoteIds = new Set(remote.map((attachment) => attachment.id));
    const keptPending = pending.filter((attachment) => !remoteIds.has(attachment.id));
    return { ...note, attachments: [...remote, ...keptPending] };
  });
}

export async function hydrateNotesWithAttachments(notes: Note[]): Promise<Note[]> {
  if (!isR2AttachmentsEnabled() || notes.length === 0) return notes;
  const metadata = await listUserAttachments();
  return mergeAttachmentsIntoNotes(notes, metadata);
}

export async function syncNoteAttachments(note: Note): Promise<Note> {
  if (!isR2AttachmentsEnabled() || note.attachments.length === 0) return note;

  const store = getAttachmentBlobStore();
  const synced: Attachment[] = [];

  for (const attachment of note.attachments) {
    if (isPendingAttachment(attachment.storagePath)) {
      const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
      const pending = takePendingAttachment(pendingId);
      if (!pending) {
        synced.push(attachment);
        continue;
      }
      const result = await store.upload(
        note.id,
        attachment.id,
        pending.blob,
        pending.mimeType,
      );
      synced.push({
        ...attachment,
        storagePath: `${ATTACHMENT_R2_PREFIX}${result.objectKey}`,
        mimeType: result.mimeType,
        sizeBytes: result.sizeBytes,
      });
      continue;
    }
    synced.push(attachment);
  }

  return { ...note, attachments: synced };
}

export async function deleteAttachmentsForNote(
  noteId: string,
  attachments: Attachment[],
): Promise<void> {
  if (!isR2AttachmentsEnabled() || attachments.length === 0) return;
  const store = getAttachmentBlobStore();
  await Promise.all(
    attachments.map(async (attachment) => {
      if (isPendingAttachment(attachment.storagePath)) return;
      try {
        await store.delete(noteId, attachment.id);
      } catch {
        // Best-effort cleanup when the note is being removed.
      }
    }),
  );
}
