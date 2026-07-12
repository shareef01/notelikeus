import { deleteNote, upsertNote } from '@/lib/firestore/notesRepository';
import { cancelNoteReminder } from '@/lib/reminders/reminderScheduler';
import { markCloudIdsForLocalDeletion } from '@/lib/notes/notesSyncService';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useToastStore } from '@/store/toastStore';
import type { Note } from '@/types/note';

async function pushNote(note: Note): Promise<void> {
  // Always save locally first
  useNotesStore.getState().upsertLocalNote(note);

  const userId = useAuthStore.getState().user?.uid;
  if (!userId) {
    console.warn('[Notelikeus] pushNote skipped — not signed in');
    return;
  }

  const syncEnabled = useSettingsStore.getState().cloudAutoSyncEnabled;
  if (!syncEnabled) {
    console.warn('[Notelikeus] pushNote skipped — cloud sync disabled in settings');
    return;
  }

  console.info('[Notelikeus] pushNote → Firestore:', note.id, note.cloudId);

  try {
    await upsertNote(userId, note);
    console.info('[Notelikeus] pushNote → success:', note.id);
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Sync failed';
    const code = (err as { code?: string }).code ?? '';
    console.error('[Notelikeus] pushNote FAILED:', code, message, err);

    let toastMsg: string;
    if (message.includes('ERR_BLOCKED_BY_CLIENT') || message.includes('Network request failed')) {
      toastMsg = 'Browser blocking Firestore. Disable ad blocker or tracking protection for this site.';
    } else if (message.includes('permission')) {
      toastMsg = 'Permission denied. Try signing out and back in.';
    } else {
      toastMsg = `Save to cloud failed: ${message}`;
    }
    useToastStore.getState().show(toastMsg, 'error');
  }
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

/** Remove locally and from Firestore when signed in. */
export async function removeNote(noteId: string): Promise<void> {
  const note = getNote(noteId);
  cancelNoteReminder(noteId);
  useNotesStore.getState().removeLocalNote(noteId);
  if (note) {
    markCloudIdsForLocalDeletion(note.cloudId);
  }
  const userId = useAuthStore.getState().user?.uid;
  if (!userId || !note) return;
  await deleteNote(userId, note);
}

export async function trashNoteById(noteId: string): Promise<Note | null> {
  const note = getNote(noteId);
  if (!note) return null;
  const updated = withTimestamp(note, { isTrashed: true, isArchived: false, isPinned: false });
  cancelNoteReminder(noteId);
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
  for (const note of trashed) {
    await removeNote(note.id);
  }
  return trashed.length;
}
