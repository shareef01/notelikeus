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
import {
  loadPendingAttachment,
  markPendingAttachmentUploaded,
  releasePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { deletePendingAttachmentsForNote } from '@/lib/local/pendingAttachmentRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
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

/**
 * Uploads pending blobs without consuming them until R2 succeeds.
 * Remaining `pending:` rows stay on the note so a later retry can finish.
 */
export async function syncNoteAttachments(note: Note): Promise<{
  note: Note;
  pendingCount: number;
  error?: Error;
}> {
  if (!isR2AttachmentsEnabled() || note.attachments.length === 0) {
    return { note, pendingCount: 0 };
  }

  const store = getAttachmentBlobStore();
  const synced: Attachment[] = [];
  let pendingCount = 0;
  let error: Error | undefined;

  for (const attachment of note.attachments) {
    if (!isPendingAttachment(attachment.storagePath)) {
      synced.push(attachment);
      continue;
    }
    const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
    const pending = await loadPendingAttachment(pendingId);
    if (!pending) {
      synced.push(attachment);
      pendingCount += 1;
      continue;
    }
    try {
      const result = await store.upload(
        note.id,
        attachment.id,
        pending.blob,
        pending.mimeType,
      );
      await markPendingAttachmentUploaded(pendingId);
      synced.push({
        ...attachment,
        storagePath: `${ATTACHMENT_R2_PREFIX}${result.objectKey}`,
        mimeType: result.mimeType,
        sizeBytes: result.sizeBytes,
      });
    } catch (uploadError) {
      error = uploadError instanceof Error ? uploadError : new Error('Attachment upload failed');
      synced.push(attachment);
      pendingCount += 1;
    }
  }

  return { note: { ...note, attachments: synced }, pendingCount, error };
}

export async function deleteAttachmentsForNote(
  noteId: string,
  attachments: Attachment[],
): Promise<void> {
  const ownerId = resolveOwnerId();
  if (ownerId) {
    await deletePendingAttachmentsForNote(ownerId, noteId);
  }
  for (const attachment of attachments) {
    if (isPendingAttachment(attachment.storagePath)) {
      await releasePendingAttachment(attachment.id);
    }
  }
  if (!isR2AttachmentsEnabled() || attachments.length === 0) return;
  const store = getAttachmentBlobStore();
  await Promise.all(
    attachments.map(async (attachment) => {
      if (isPendingAttachment(attachment.storagePath)) return;
      try {
        await store.delete(noteId, attachment.id);
      } catch {
        // Best-effort remote cleanup when the note is being removed.
      }
    }),
  );
}
