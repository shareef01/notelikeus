import { deleteCloudTombstone } from '@/lib/notes/tombstones';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import {
  deleteAttachmentsForNote,
  syncNoteAttachments,
} from '@/lib/attachments/attachmentSyncService';
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
  useNotesStore.getState().upsertLocalNote(note);
  await persistLocalNote(note);
  const userId = useAuthStore.getState().user?.uid;
  if (!userId) return;
  await getRemoteNotesDataSource().upsertNote(userId, note);
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
  let toSave = note;
  if (isR2AttachmentsEnabled()) {
    toSave = await syncNoteAttachments(note);
    if (existing) {
      const nextIds = new Set(toSave.attachments.map((attachment) => attachment.id));
      const removed = existing.attachments.filter(
        (attachment) => !nextIds.has(attachment.id),
      );
      await deleteAttachmentsForNote(note.id, removed);
    }
  }
  if (existing && notesEqual(existing, toSave)) return;
  await pushNote(toSave);
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
  if (existing) {
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
  if (userId) {
    // Must go before upsertNote: upsertNote re-reads the cloud tombstone and would
    // otherwise merge it back and delete the doc again.
    await deleteCloudTombstone(userId, note.id);
  }
  useTombstoneStore.getState().clearIds([note.id]);
  useNotesStore.getState().upsertLocalNote(note);
  await persistLocalNote(note);
  if (userId) {
    await getRemoteNotesDataSource().upsertNote(userId, note);
  }
}
