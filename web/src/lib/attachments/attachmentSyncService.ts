import {
  attachmentFromMetadata,
  ATTACHMENT_PENDING_PREFIX,
  ATTACHMENT_R2_PREFIX,
  isPendingAttachment,
} from '@/lib/attachments/attachmentPaths';
import { buildAttachmentObjectKey } from '@/lib/attachments/attachmentObjectKey';
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
import { listPendingPushes } from '@/lib/local/notesLocalRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Attachment } from '@/types/attachment';
import type { Note } from '@/types/note';

/**
 * Rebuilds each note's attachments from the account's committed attachment rows, keeping the
 * `pending:` ones (not uploaded yet).
 *
 * [awaitingCommit] are notes with a local edit the cloud has not confirmed. Their uploads stay
 * provisional, and so unlisted, until that edit's commit names them — dropping them here would
 * erase the reference the commit is about to make — so their `r2:` attachments are kept too.
 */
export function mergeAttachmentsIntoNotes(
  notes: Note[],
  metadata: NoteAttachmentMetadata[],
  awaitingCommit: ReadonlySet<string> = new Set(),
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
    const pending = note.attachments.filter(
      (attachment) =>
        isPendingAttachment(attachment.storagePath) ||
        (awaitingCommit.has(note.id) && attachment.storagePath.startsWith(ATTACHMENT_R2_PREFIX)),
    );
    const remoteIds = new Set(remote.map((attachment) => attachment.id));
    const keptPending = pending.filter((attachment) => !remoteIds.has(attachment.id));
    return { ...note, attachments: [...remote, ...keptPending] };
  });
}

export async function hydrateNotesWithAttachments(notes: Note[]): Promise<Note[]> {
  if (!isR2AttachmentsEnabled() || notes.length === 0) return notes;
  const metadata = await listUserAttachments();
  return mergeAttachmentsIntoNotes(notes, metadata, await notesAwaitingCommit());
}

async function notesAwaitingCommit(): Promise<Set<string>> {
  const ownerId = resolveOwnerId();
  if (!ownerId) return new Set();
  try {
    return new Set(Object.keys(await listPendingPushes(ownerId)));
  } catch {
    return new Set();
  }
}

/**
 * Some of a note's uploads failed. [note] carries every attachment that did reach R2 rewritten to its
 * `r2:` path, and every other one unchanged, so the caller can still persist what succeeded.
 */
export class AttachmentSyncIncompleteError extends Error {
  readonly note: Note;
  readonly failures: unknown[];

  constructor(note: Note, failures: unknown[]) {
    super(
      `${failures.length} attachment upload${failures.length === 1 ? '' : 's'} failed: ${
        failures[0] instanceof Error ? failures[0].message : 'unknown failure'
      }`,
    );
    this.name = 'AttachmentSyncIncompleteError';
    this.note = note;
    this.failures = failures;
  }
}

/**
 * Uploads a note's staged attachments and returns the note rewritten to the paths R2 accepted.
 *
 * Releases **nothing**: the staged copy is the only one this device has until a persisted note points
 * at the uploaded one, so the caller releases with {@link releaseUploadedPendingAttachments} after it
 * has persisted the result. Releasing here, as this used to, lost an image whenever a later upload in
 * the same note failed: the persisted note still said `pending:` for bytes that were gone, the next
 * attempt stopped at "missing local blob", and the provisional upload was never committed, so the
 * 90-day sweep removed it (AUD-011).
 *
 * Every attachment is attempted even after one fails, and a failure throws
 * {@link AttachmentSyncIncompleteError} carrying the partly rewritten note.
 */
export async function syncNoteAttachments(note: Note): Promise<Note> {
  if (!isR2AttachmentsEnabled() || note.attachments.length === 0) return note;

  const store = getAttachmentBlobStore();
  const synced: Attachment[] = [];
  const failures: unknown[] = [];

  for (const attachment of note.attachments) {
    if (!isPendingAttachment(attachment.storagePath)) {
      synced.push(attachment);
      continue;
    }
    const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
    try {
      // eslint-disable-next-line no-await-in-loop -- one attachment at a time, in note order
      const pending = peekPendingAttachment(pendingId) ?? (await getPendingAttachmentBlob(pendingId, note.id));
      if (!pending) {
        // eslint-disable-next-line no-await-in-loop -- one attachment at a time, in note order
        synced.push(await recoverUploadedAttachment(note, attachment, pendingId));
        continue;
      }
      // eslint-disable-next-line no-await-in-loop -- one attachment at a time, in note order
      const result = await store.upload(note.id, attachment.id, pending.blob, pending.mimeType);
      synced.push({
        ...attachment,
        storagePath: `${ATTACHMENT_R2_PREFIX}${result.objectKey}`,
        mimeType: result.mimeType,
        sizeBytes: result.sizeBytes,
      });
    } catch (error) {
      // The reference stays exactly as it was, so a later save retries it.
      failures.push(error);
      synced.push(attachment);
    }
  }

  const result = { ...note, attachments: synced };
  if (failures.length > 0) throw new AttachmentSyncIncompleteError(result, failures);
  return result;
}

/**
 * A staged reference whose bytes are no longer on this device.
 *
 * The one way that happens is the release-before-persist defect above, which uploaded the bytes and
 * then dropped them, so they may well be in R2 as a provisional upload. If the Worker can serve them
 * (it authorizes a provisional row like a committed one), the reference is rewritten to that upload,
 * and the note's next commit names it and makes it permanent. If not, the reference is left alone:
 * dropping it would decide for the user that the image is gone.
 */
async function recoverUploadedAttachment(
  note: Note,
  attachment: Attachment,
  pendingId: string,
): Promise<Attachment> {
  const ownerId = resolveOwnerId();
  if (!ownerId) throw new Error(`Missing local blob for pending attachment ${pendingId}`);
  let blob: Blob | undefined;
  try {
    blob = await getAttachmentBlobStore().download(note.id, attachment.id);
  } catch {
    blob = undefined;
  }
  // Only a real, non-empty body proves the upload landed.
  if (!(blob instanceof Blob) || blob.size === 0) {
    throw new Error(`Missing local blob for pending attachment ${pendingId}`);
  }
  return {
    ...attachment,
    storagePath: `${ATTACHMENT_R2_PREFIX}${buildAttachmentObjectKey(ownerId, note.id, attachment.id)}`,
    mimeType: attachment.mimeType ?? (blob.type || undefined),
    sizeBytes: blob.size,
  };
}

/**
 * Releases the staged copies the upload made redundant, once [uploaded] is persisted.
 *
 * Only an attachment that was staged in [snapshot] **and** carries an `r2:` path in [uploaded] is
 * released: that is what proves another copy exists. Deciding from [uploaded] alone would release one
 * that the upload deliberately kept staged because it failed. The same rule as Kotlin's
 * `AttachmentSyncService.confirmCommittedAttachments`.
 */
export async function releaseUploadedPendingAttachments(
  snapshot: Note,
  uploaded: Note,
  ownerId?: string,
): Promise<void> {
  const nowInR2 = new Set(
    uploaded.attachments
      .filter((attachment) => attachment.storagePath.startsWith(ATTACHMENT_R2_PREFIX))
      .map((attachment) => attachment.id),
  );
  for (const attachment of snapshot.attachments) {
    if (!isPendingAttachment(attachment.storagePath) || !nowInR2.has(attachment.id)) continue;
    // eslint-disable-next-line no-await-in-loop -- best-effort deletes, one at a time
    await releasePendingAttachment(
      attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length),
      uploaded.id,
      ownerId,
    );
  }
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
