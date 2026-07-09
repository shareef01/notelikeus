import { deleteNote, upsertNote } from '@/lib/firestore/notesRepository';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import type { Note } from '@/types/note';

/** Save locally and optionally push to Firestore — no React hooks. */
export async function saveNote(note: Note): Promise<void> {
  useNotesStore.getState().upsertLocalNote(note);
  const userId = useAuthStore.getState().user?.uid;
  if (!userId || !useSettingsStore.getState().cloudAutoSyncEnabled) return;
  await upsertNote(userId, note);
}

/** Remove locally and from Firestore when signed in. */
export async function removeNote(noteId: string): Promise<void> {
  useNotesStore.getState().removeLocalNote(noteId);
  const userId = useAuthStore.getState().user?.uid;
  if (!userId) return;
  await deleteNote(userId, noteId);
}
