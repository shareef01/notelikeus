import {
  attachmentFromMetadata,
  ATTACHMENT_PENDING_PREFIX,
  ATTACHMENT_R2_PREFIX,
  isPendingAttachment,
} from '@/lib/attachments/attachmentPaths';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import {
  listPendingDeletedAttachments,
  listUserAttachments,
  purgeDeletedNoteAttachment,
  type NoteAttachmentMetadata,
} from '@/lib/attachments/supabaseAttachmentMetadata';
import {
  getPendingAttachmentBlob,
  peekPendingAttachment,
  releasePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
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
      const pending =
        peekPendingAttachment(pendingId) ??
        (await getPendingAttachmentBlob(pendingId, note.id));
      if (!pending) {
        throw new Error(`Missing local blob for pending attachment ${pendingId}`);
      }
      const result = await store.upload(
        note.id,
        attachment.id,
        pending.blob,
        pending.mimeType,
      );
      await releasePendingAttachment(pendingId, note.id);
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
      if (isPendingAttachment(attachment.storagePath)) {
        await releasePendingAttachment(
          attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length),
          noteId,
        );
        return;
      }
      try {
        await store.delete(noteId, attachment.id);
      } catch {
        // Live-note edits prefer an orphan blob over failing the save.
      }
    }),
  );
}

/**
 * R2 GC after the server note delete has committed. Throws if any remote delete
 * fails so the pending-GC marker stays and a later pull can retry.
 */
export async function gcAttachmentsAfterNoteDelete(
  noteId: string,
  attachments: Attachment[],
): Promise<void> {
  if (!isR2AttachmentsEnabled() || attachments.length === 0) return;
  const store = getAttachmentBlobStore();
  const failures: unknown[] = [];
  for (const attachment of attachments) {
    if (isPendingAttachment(attachment.storagePath)) {
      await releasePendingAttachment(
        attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length),
        noteId,
      );
      continue;
    }
    try {
      await store.delete(noteId, attachment.id);
    } catch (error) {
      failures.push(error);
    }
  }
  if (failures.length > 0) {
    throw failures[0] instanceof Error
      ? failures[0]
      : new Error(`Attachment GC failed for note ${noteId}`);
  }
}

export async function retryPendingAttachmentGc(): Promise<void> {
  const tomb = useTombstoneStore.getState();
  for (const [noteId, attachmentIds] of tomb.pendingAttachmentGcEntries()) {
    if (tomb.isRestored(noteId)) {
      tomb.clearPendingAttachmentGc(noteId);
      continue;
    }
    const attachments = attachmentIds.map((id) => ({
      id,
      noteId: Number.parseInt(noteId, 10) || 0,
      storagePath: `${ATTACHMENT_R2_PREFIX}gc/${noteId}/${id}`,
      type: 'image',
    }));
    try {
      await gcAttachmentsAfterNoteDelete(noteId, attachments);
      useTombstoneStore.getState().clearPendingAttachmentGc(noteId);
    } catch {
      // Marker stays; the next snapshot or pull retries.
    }
  }
  await sweepServerPendingDeletedAttachments();
}

async function sweepServerPendingDeletedAttachments(): Promise<void> {
  if (!isR2AttachmentsEnabled()) return;
  let pending;
  try {
    pending = await listPendingDeletedAttachments();
  } catch {
    return;
  }
  const tomb = useTombstoneStore.getState();
  const store = getAttachmentBlobStore();
  for (const row of pending) {
    if (tomb.isRestored(row.noteId)) continue;
    try {
      await store.delete(row.noteId, row.attachmentId);
      await purgeDeletedNoteAttachment(row.attachmentId, row.noteId);
    } catch {
      // Prefer orphan storage; the next pull retries.
    }
  }
}
