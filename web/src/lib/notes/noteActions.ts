import { restoreCloudNote } from '@/lib/notes/tombstones';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { isPendingAttachment } from '@/lib/attachments/attachmentPaths';
import { deleteNote as deleteLocalIndexedDbNote, putNote } from '@/lib/local/notesLocalRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { notesEqual } from '@/lib/notes/noteEquality';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';

async function persistLocalNote(note: Note): Promise<void> {
  const ownerId = resolveOwnerId();
  if (!ownerId) return;
  await putNote(ownerId, note);
}

async function pushNote(note: Note): Promise<void> {
  // Persist-first: commit to IndexedDB before mutating memory
  await persistLocalNote(note);
  useNotesStore.getState().upsertLocalNote(note);
  const userId = useAuthStore.getState().user?.uid;
  if (!userId) return;
  try {
    await getRemoteNotesDataSource().upsertNote(userId, note);
  } catch (error) {
    // If Supabase fails, DO NOT rollback local note.
    // Local edit remains committed. Reconciliation retries remote later.
    console.warn('Remote note upsert failed, note remains saved locally:', error);
  }
}

function getNote(noteId: string): Note | undefined {
  return useNotesStore.getState().notes.find((note) => note.id === noteId);
}

function withTimestamp(note: Note, patch: Partial<Note>): Note {
  return { ...note, ...patch, timestamp: Date.now() };
}

/** Save locally and optionally push to the cloud when signed in — no React hooks. */
export async function saveNote(note: Note): Promise<void> {
  const existing = getNote(note.id);
  const toSave = note;

  // Clean up removed attachments if any
  if (isR2AttachmentsEnabled() && existing) {
    const { deleteAttachmentsForNote } = await import(
      '@/lib/attachments/attachmentSyncService'
    );
    const nextIds = new Set(toSave.attachments.map((attachment) => attachment.id));
    const removed = existing.attachments.filter(
      (attachment) => !nextIds.has(attachment.id),
    );
    if (removed.length > 0) {
      await deleteAttachmentsForNote(note.id, removed);
    }
  }

  // Local data is primary: text note persistence must NOT wait for cloud attachment upload!
  if (existing && notesEqual(existing, toSave)) return;
  await pushNote(toSave);

  // If there are pending attachments and R2 is enabled, attempt upload
  const hasPending = toSave.attachments.some((a) => isPendingAttachment(a.storagePath));
  if (isR2AttachmentsEnabled() && hasPending) {
    const { syncNoteAttachments } = await import(
      '@/lib/attachments/attachmentSyncService'
    );
    try {
      const synced = await syncNoteAttachments(toSave);
      if (!notesEqual(toSave, synced)) {
        await persistLocalNote(synced);
        useNotesStore.getState().upsertLocalNote(synced);
        const userId = useAuthStore.getState().user?.uid;
        if (userId) {
          try {
            await getRemoteNotesDataSource().upsertNote(userId, synced);
          } catch {
            // Transient network failure; retry on reconciliation
          }
        }
      }
    } catch (error) {
      // Offline/Worker failure: note text and local pending blobs survive safely
      console.warn('Attachment upload failed or offline; note text remains durably saved locally:', error);
    }
  }
}

/** Remove locally and from the cloud when signed in. Tombstoned so a later cloud
 * merge can never resurrect it, even if the remote delete below fails or a stale
 * copy exists from before this device last synced. Guest-mode deletes skip the
 * tombstone: guest notes live in IndexedDB only, so a persisted tombstone could
 * later suppress an unrelated real cloud note that happens to reuse the same id. */
export async function removeNote(noteId: string): Promise<void> {
  const existing = getNote(noteId);
  const isGuest = useAuthStore.getState().guestMode;
  const attachments = existing?.attachments ?? [];
  const ownerId = resolveOwnerId();

  // Persist-first: commit deletion to IndexedDB before mutating in-memory store
  if (ownerId) {
    await deleteLocalIndexedDbNote(ownerId, noteId);
  }

  if (!isGuest) {
    useTombstoneStore.getState().markDeleted(noteId);
  }
  useNotesStore.getState().removeLocalNote(noteId);

  const userId = useAuthStore.getState().user?.uid;
  if (userId) {
    // Remote delete: locally committed delete stays deleted/tombstoned even if remote fails
    await getRemoteNotesDataSource().deleteNote(userId, noteId);
  }

  if (!isR2AttachmentsEnabled() || attachments.length === 0) return;
  const { gcAttachmentsAfterNoteDelete } = await import(
    '@/lib/attachments/attachmentSyncService'
  );
  useTombstoneStore.getState().markPendingAttachmentGc(
    noteId,
    attachments.map((attachment) => attachment.id),
  );
  try {
    await gcAttachmentsAfterNoteDelete(noteId, attachments);
    useTombstoneStore.getState().clearPendingAttachmentGc(noteId);
  } catch {
    // Note stays deleted; GC retries on the next snapshot or pull.
  }
}

export async function trashNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: true, isArchived: false, isPinned: false });
  await pushNote(updated);
  return updated;
}

export async function restoreNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: false, isArchived: false });
  await pushNote(updated);
  return updated;
}

export async function archiveNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isArchived: true, isTrashed: false, isPinned: false });
  await pushNote(updated);
  return updated;
}

export async function unarchiveNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isArchived: false });
  await pushNote(updated);
  return updated;
}

export async function emptyTrash(): Promise<number> {
  const trashed = useNotesStore.getState().notes.filter((note) => note.isTrashed);
  await Promise.all(trashed.map((note) => removeNote(note.id)));
  return trashed.length;
}

/** Reverse a permanent delete within the undo window. Clears both tombstones so the
 * realtime listener and future merges (which suppress tombstoned ids) keep the note live. */
export async function restorePermanentlyDeletedNote(note: Note): Promise<void> {
  const userId = useAuthStore.getState().user?.uid;
  useTombstoneStore.getState().markRestored(note.id);

  // Persist-first: commit to IndexedDB before clearing tombstone protection
  try {
    await persistLocalNote(note);
  } catch (error) {
    useTombstoneStore.getState().clearRestored([note.id]);
    throw error;
  }

  useTombstoneStore.getState().clearIds([note.id]);
  useNotesStore.getState().upsertLocalNote(note);
  if (userId) {
    await restoreCloudNote(userId, note);
  }
  useTombstoneStore.getState().clearRestored([note.id]);
}
