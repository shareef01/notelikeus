import { deleteNote, upsertNote } from '@/lib/firestore/notesRepository';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';

async function pushNote(note: Note): Promise<void> {
  useNotesStore.getState().upsertLocalNote(note);
  const userId = useAuthStore.getState().user?.uid;
  if (!userId || !useSettingsStore.getState().cloudAutoSyncEnabled) return;
  await upsertNote(userId, note);
}

function getNote(noteId: string): Note | undefined {
  return useNotesStore.getState().notes.find((note) => note.id === noteId);
}

function withTimestamp(note: Note, patch: Partial<Note>): Note {
  return { ...note, ...patch, timestamp: Date.now() };
}

/** Save locally and optionally push to Firestore — no React hooks. */
export async function saveNote(note: Note): Promise<void> {
  await pushNote(note);
}

/** Remove locally and from Firestore when signed in. Tombstoned so a later cloud
 * merge can never resurrect it, even if the remote delete below fails or a stale
 * copy exists from before this device last synced. */
export async function removeNote(noteId: string): Promise<void> {
  useTombstoneStore.getState().markDeleted(noteId);
  useNotesStore.getState().removeLocalNote(noteId);
  const userId = useAuthStore.getState().user?.uid;
  if (!userId) return;
  await deleteNote(userId, noteId);
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
