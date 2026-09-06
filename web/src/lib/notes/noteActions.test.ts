import { beforeEach, describe, expect, it, vi } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    upsertNote: vi.fn().mockResolvedValue(undefined),
    deleteNote: vi.fn().mockResolvedValue(undefined),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

vi.mock('@/lib/local/notesLocalRepository', () => ({
  putNote: vi.fn().mockResolvedValue(undefined),
  deleteNote: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@/lib/notes/tombstones', () => ({
  restoreCloudNote: vi.fn().mockResolvedValue(undefined),
}));

const { attachmentMocks } = vi.hoisted(() => ({
  attachmentMocks: {
    isR2AttachmentsEnabled: vi.fn(() => false),
    gcAttachmentsAfterNoteDelete: vi.fn().mockResolvedValue(undefined),
    deleteAttachmentsForNote: vi.fn().mockResolvedValue(undefined),
    syncNoteAttachments: vi.fn(async (note: unknown) => note),
  },
}));

vi.mock('@/lib/attachments/attachmentConfig', () => ({
  isR2AttachmentsEnabled: () => attachmentMocks.isR2AttachmentsEnabled(),
}));

vi.mock('@/lib/attachments/attachmentSyncService', () => ({
  gcAttachmentsAfterNoteDelete: (noteId: string, attachments: unknown) =>
    attachmentMocks.gcAttachmentsAfterNoteDelete(noteId, attachments),
  deleteAttachmentsForNote: (noteId: string, attachments: unknown) =>
    attachmentMocks.deleteAttachmentsForNote(noteId, attachments),
  syncNoteAttachments: (note: unknown) => attachmentMocks.syncNoteAttachments(note),
}));

import { restoreCloudNote } from '@/lib/notes/tombstones';
import { deleteNote, putNote } from '@/lib/local/notesLocalRepository';
import { removeNote, restorePermanentlyDeletedNote, saveNote } from '@/lib/notes/noteActions';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

function makeNote() {
  return createEmptyNote({ id: '1', localId: 1, timestamp: 1, title: 'Note', content: 'Body' });
}

describe('saveNote', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    attachmentMocks.syncNoteAttachments.mockImplementation(async (note: unknown) => note);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('saves locally and uploads to the cloud when signed in', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });

    await saveNote(makeNote());

    expect(remoteMocks.upsertNote).toHaveBeenCalledWith(
      'user-1',
      expect.objectContaining({ id: '1' }),
    );
    expect(putNote).toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((note) => note.id === '1')).toBe(true);
  });

  it('saves locally only when signed out', async () => {
    await saveNote(makeNote());

    expect(remoteMocks.upsertNote).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((note) => note.id === '1')).toBe(true);
  });

  it('skips no-op saves for an unchanged note', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = makeNote();

    await saveNote(note);
    await saveNote(note);

    expect(remoteMocks.upsertNote).toHaveBeenCalledTimes(1);
  });

  it('saves note text locally even when attachment upload is offline / fails', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    attachmentMocks.syncNoteAttachments.mockRejectedValueOnce(
      new Error('R2 Worker temporary 503'),
    );
    const note = makeNote();

    // The note text MUST save locally without throwing
    await saveNote(note);

    expect(putNote).toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(true);
  });

  it('does not mutate in-memory store when IndexedDB putNote fails', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    vi.mocked(putNote).mockRejectedValueOnce(new Error('IndexedDB disk full'));
    const note = makeNote();

    await expect(saveNote(note)).rejects.toThrow(/IndexedDB disk full/);

    // In-memory store must not falsely contain the unpersisted note
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(false);
  });

  it('keeps note durable in IndexedDB and memory when remote upsert fails', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    remoteMocks.upsertNote.mockRejectedValueOnce(new Error('Supabase 503 outage'));
    const note = makeNote();

    // Local-first: remote error does not fail the local save
    await saveNote(note);

    // Locally committed note remains durable in store and IndexedDB
    expect(putNote).toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(true);
  });
});

describe('restorePermanentlyDeletedNote', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    vi.mocked(restoreCloudNote).mockResolvedValue(undefined);
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
    expect(restoreCloudNote).not.toHaveBeenCalled();
    expect(remoteMocks.upsertNote).not.toHaveBeenCalled();
  });

  it('restores through the atomic RPC when signed in', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = makeNote();
    await removeNote(note.id);
    expect(remoteMocks.deleteNote).toHaveBeenCalledWith('user-1', note.id);

    await restorePermanentlyDeletedNote(note);

    expect(restoreCloudNote).toHaveBeenCalledWith('user-1', expect.objectContaining({ id: '1' }));
    expect(remoteMocks.upsertNote).not.toHaveBeenCalled();
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(useTombstoneStore.getState().isRestored(note.id)).toBe(false);
    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(true);
  });

  it('clears the local tombstone and keeps a restore marker when the RPC fails', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = makeNote();
    await removeNote(note.id);
    vi.mocked(restoreCloudNote).mockRejectedValueOnce(new Error('rpc failed'));

    await expect(restorePermanentlyDeletedNote(note)).rejects.toThrow(/rpc failed/);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(useTombstoneStore.getState().isRestored(note.id)).toBe(true);
    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(true);
  });

  it('does not prematurely clear tombstone when IndexedDB putNote fails during restore', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = makeNote();
    useTombstoneStore.getState().markDeleted(note.id);
    vi.mocked(putNote).mockRejectedValueOnce(new Error('IndexedDB restore failure'));

    await expect(restorePermanentlyDeletedNote(note)).rejects.toThrow(/IndexedDB restore failure/);

    // Tombstone MUST NOT be cleared if durable persistence failed
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(false);
  });
});

describe('removeNote', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(false);
    attachmentMocks.gcAttachmentsAfterNoteDelete.mockResolvedValue(undefined);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('does not remove from memory or tombstone when IndexedDB deleteNote fails', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = makeNote();
    useNotesStore.getState().setNotes([note]);
    vi.mocked(deleteNote).mockRejectedValueOnce(new Error('IndexedDB delete failed'));

    await expect(removeNote(note.id)).rejects.toThrow(/IndexedDB delete failed/);

    // Note must not enter incoherent state: stays in memory, not tombstoned
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(true);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
  });

  it('keeps locally committed delete tombstoned and removed when remote delete fails', async () => {
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = makeNote();
    useNotesStore.getState().setNotes([note]);
    remoteMocks.deleteNote.mockRejectedValueOnce(new Error('Supabase network timeout'));

    await expect(removeNote(note.id)).rejects.toThrow(/Supabase network timeout/);

    // Local commit succeeded: note stays deleted and tombstoned
    expect(deleteNote).toHaveBeenCalledWith('user-1', note.id);
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(false);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
  });

  it('skips the tombstone in guest mode so a guest delete can never suppress a real cloud note', async () => {
    useAuthStore.getState().enterGuestMode();
    const note = makeNote();
    await saveNote(note);
    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(true);

    await removeNote(note.id);

    expect(useNotesStore.getState().notes.some((entry) => entry.id === note.id)).toBe(false);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(remoteMocks.deleteNote).not.toHaveBeenCalled();
  });

  it('does not GC attachments when the server note delete fails', async () => {
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(true);
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = {
      ...makeNote(),
      attachments: [{
        id: 'att-1',
        noteId: 1,
        storagePath: 'r2:owners/u/notes/1/att-1',
        type: 'image',
      }],
    };
    useNotesStore.getState().setNotes([note]);
    remoteMocks.deleteNote.mockRejectedValueOnce(new Error('rpc failed'));

    await expect(removeNote(note.id)).rejects.toThrow(/rpc failed/);
    expect(attachmentMocks.gcAttachmentsAfterNoteDelete).not.toHaveBeenCalled();
    expect(useTombstoneStore.getState().pendingAttachmentGcByNoteId[note.id]).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
  });

  it('GCs attachments only after the server delete and keeps a retry marker on GC failure', async () => {
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(true);
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = {
      ...makeNote(),
      attachments: [{
        id: 'att-1',
        noteId: 1,
        storagePath: 'r2:owners/u/notes/1/att-1',
        type: 'image',
      }],
    };
    useNotesStore.getState().setNotes([note]);
    const order: string[] = [];
    remoteMocks.deleteNote.mockImplementation(async () => {
      order.push('deleteNote');
    });
    attachmentMocks.gcAttachmentsAfterNoteDelete.mockImplementation(async () => {
      order.push('gc');
      throw new Error('r2 unavailable');
    });

    await removeNote(note.id);

    expect(order).toEqual(['deleteNote', 'gc']);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    expect(useTombstoneStore.getState().pendingAttachmentGcByNoteId[note.id]).toEqual(['att-1']);
  });
});
