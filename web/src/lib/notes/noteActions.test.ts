import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/firestore/notesRepository', () => ({
  upsertNote: vi.fn().mockResolvedValue(undefined),
  deleteNote: vi.fn().mockResolvedValue(undefined),
}));

import { upsertNote } from '@/lib/firestore/notesRepository';
import { saveNote } from '@/lib/notes/noteActions';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';
import type { User } from 'firebase/auth';

describe('saveNote', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
  });

  it('saves locally and uploads to Firestore when signed in', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1' } as User);

    await saveNote(createEmptyNote({ id: '1', localId: 1, timestamp: 1 }));

    expect(upsertNote).toHaveBeenCalledWith('user-1', expect.objectContaining({ id: '1' }));
    expect(useNotesStore.getState().notes.some((note) => note.id === '1')).toBe(true);
  });

  it('saves locally only when signed out', async () => {
    await saveNote(createEmptyNote({ id: '1', localId: 1, timestamp: 1 }));

    expect(upsertNote).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((note) => note.id === '1')).toBe(true);
  });

  it('skips no-op saves for an unchanged note', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1' } as User);
    const note = createEmptyNote({ id: '1', localId: 1, timestamp: 1, title: 'Same note' });

    await saveNote(note);
    await saveNote(note);

    expect(upsertNote).toHaveBeenCalledTimes(1);
  });
});
