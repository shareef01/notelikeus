import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { isPendingAttachment } from '@/lib/attachments/attachmentPaths';
import { deleteCloudTombstone } from '@/lib/notes/tombstones';
import { deleteNote as deleteLocalIndexedDbNote, putNote } from '@/lib/local/notesLocalRepository';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { notesEqual } from '@/lib/notes/noteEquality';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { sanitizeSyncErrorMessage } from '@/lib/remote/remoteErrors';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useSyncStore } from '@/store/syncStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';

export interface SaveNoteResult {
  localSaved: true;
  remoteSynced: boolean;
  attachmentPending: boolean;
  error?: Error;
}

async function persistLocalNote(note: Note): Promise<void> {
  const ownerId = resolveOwnerId();
  if (!ownerId) return;
  await putNote(ownerId, note);
}

function persistMemoryAndDisk(note: Note): Promise<void> {
  useNotesStore.getState().upsertLocalNote(note);
  return persistLocalNote(note);
}

function getNote(noteId: string): Note | undefined {
  return useNotesStore.getState().notes.find((note) => note.id === noteId);
}

function withTimestamp(note: Note, patch: Partial<Note>): Note {
  return { ...note, ...patch, timestamp: Date.now() };
}

/** Save locally first. Cloud/R2 failures stay retryable and never unwind the local write. */
export async function saveNote(note: Note): Promise<SaveNoteResult> {
  const existing = getNote(note.id);
  const hasPendingAttachments = note.attachments.some((attachment) =>
    isPendingAttachment(attachment.storagePath),
  );
  if (existing && notesEqual(existing, note) && !hasPendingAttachments) {
    return { localSaved: true, remoteSynced: true, attachmentPending: false };
  }

  await persistMemoryAndDisk(note);
  useSyncStore.getState().markPendingLocalMutations(true);

  let toSave = note;
  let attachmentPending = note.attachments.some((attachment) =>
    isPendingAttachment(attachment.storagePath),
  );
  let error: Error | undefined;

  if (isR2AttachmentsEnabled()) {
    const { syncNoteAttachments, deleteAttachmentsForNote } = await import(
      '@/lib/attachments/attachmentSyncService'
    );
    const synced = await syncNoteAttachments(note);
    toSave = synced.note;
    attachmentPending = synced.pendingCount > 0;
    error = synced.error;
    if (!notesEqual(note, toSave)) {
      await persistMemoryAndDisk(toSave);
    }
    if (existing) {
      const nextIds = new Set(toSave.attachments.map((attachment) => attachment.id));
      const removed = existing.attachments.filter(
        (attachment) => !nextIds.has(attachment.id),
      );
      await deleteAttachmentsForNote(note.id, removed);
    }
  }

  const userId = useAuthStore.getState().user?.uid;
  if (!userId) {
    if (!attachmentPending && !error) {
      useSyncStore.getState().markPendingLocalMutations(false);
    }
    return { localSaved: true, remoteSynced: false, attachmentPending, error };
  }

  try {
    useSyncStore.getState().markSyncing('upsert');
    await getRemoteNotesDataSource().upsertNote(userId, toSave);
    if (!attachmentPending) {
      useSyncStore.getState().markPendingLocalMutations(false);
    }
    useSyncStore.getState().markReconcileSuccess();
    return { localSaved: true, remoteSynced: true, attachmentPending, error };
  } catch (remoteError) {
    const wrapped =
      remoteError instanceof Error ? remoteError : new Error('Cloud save failed');
    useSyncStore.getState().markError(sanitizeSyncErrorMessage(wrapped));
    return {
      localSaved: true,
      remoteSynced: false,
      attachmentPending,
      error: error ?? wrapped,
    };
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
  if (!isGuest) {
    useTombstoneStore.getState().markDeleted(noteId);
  }
  if (existing && isR2AttachmentsEnabled()) {
    const { deleteAttachmentsForNote } = await import('@/lib/attachments/attachmentSyncService');
    await deleteAttachmentsForNote(noteId, existing.attachments);
  }
  useNotesStore.getState().removeLocalNote(noteId);
  const ownerId = resolveOwnerId();
  if (ownerId) {
    await deleteLocalIndexedDbNote(ownerId, noteId);
  }
  const userId = useAuthStore.getState().user?.uid;
  if (!userId) return;
  await getRemoteNotesDataSource().deleteNote(userId, noteId);
}

export async function trashNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: true, isArchived: false, isPinned: false });
  await saveNote(updated);
  return updated;
}

export async function restoreNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: false, isArchived: false });
  await saveNote(updated);
  return updated;
}

export async function archiveNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isArchived: true, isTrashed: false, isPinned: false });
  await saveNote(updated);
  return updated;
}

export async function unarchiveNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isArchived: false });
  await saveNote(updated);
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
  if (userId) {
    await deleteCloudTombstone(userId, note.id);
  }
  useTombstoneStore.getState().clearIds([note.id]);
  useNotesStore.getState().upsertLocalNote(note);
  await persistLocalNote(note);
  if (userId) {
    await getRemoteNotesDataSource().upsertNote(userId, note);
  }
}
