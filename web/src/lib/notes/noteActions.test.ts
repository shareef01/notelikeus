import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/firestore/notesRepository', () => ({
  upsertNote: vi.fn().mockResolvedValue(undefined),
  deleteNote: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@/lib/firestore/tombstones', () => ({
  deleteCloudTombstone: vi.fn().mockResolvedValue(undefined),
}));

import { deleteNote, upsertNote } from '@/lib/firestore/notesRepository';
import { deleteCloudTombstone } from '@/lib/firestore/tombstones';
import { removeNote, restorePermanentlyDeletedNote, saveNote } from '@/lib/notes/noteActions';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';
import type { User } from 'firebase/auth';

function makeNote() {
  return createEmptyNote({ id: '1', localId: 1, timestamp: 1, title: 'Note', content: 'Body' });
}

describe('saveNote', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('saves locally and uploads to Firestore when signed in', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1' } as User);

    await saveNote(makeNote());

    expect(upsertNote).toHaveBeenCalledWith('user-1', expect.objectContaining({ id: '1' }));
    expect(useNotesStore.getState().notes.some((note) => note.id === '1')).toBe(true);
  });

  it('saves locally only when signed out', async () => {
    await saveNote(makeNote());

    expect(upsertNote).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((note) => note.id === '1')).toBe(true);
  });

  it('skips no-op saves for an unchanged note', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1' } as User);
    const note = makeNote();

    await saveNote(note);
    await saveNote(note);

    expect(upsertNote).toHaveBeenCalledTimes(1);
  });
});

describe('restorePermanentlyDeletedNote', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('re-adds the note locally and clears the tombstone when signed out', async () => {
    const note = makeNote();
    await removeNote(note.id);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(false);

    await restorePermanentlyDeletedNote(note);

    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(true);
    expect(deleteCloudTombstone).not.toHaveBeenCalled();
    expect(upsertNote).not.toHaveBeenCalled();
  });

  it('deletes the cloud tombstone and re-uploads to Firestore when signed in', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1' } as User);
    const note = makeNote();
    await removeNote(note.id);
    expect(deleteNote).toHaveBeenCalledWith('user-1', note.id);

    await restorePermanentlyDeletedNote(note);

    expect(deleteCloudTombstone).toHaveBeenCalledWith('user-1', note.id);
    expect(upsertNote).toHaveBeenCalledWith('user-1', expect.objectContaining({ id: '1' }));
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(true);
  });

  it('clears the cloud tombstone before upserting so it cannot be re-suppressed', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1' } as User);
    const note = makeNote();

    await restorePermanentlyDeletedNote(note);

    const tombstoneCallOrder = vi.mocked(deleteCloudTombstone).mock.invocationCallOrder[0];
    const upsertCallOrder = vi.mocked(upsertNote).mock.invocationCallOrder[0];
    expect(tombstoneCallOrder).toBeLessThan(upsertCallOrder);
  });
});
