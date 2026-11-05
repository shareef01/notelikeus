import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { remoteMocks, metadataMocks } = vi.hoisted(() => ({
  remoteMocks: {
    upsertNote: vi.fn().mockResolvedValue(undefined),
    deleteNote: vi.fn().mockResolvedValue(undefined),
  },
  metadataMocks: {
    listUserAttachments: vi.fn().mockResolvedValue([]),
    listPendingDeletedAttachments: vi.fn().mockResolvedValue([]),
    purgeDeletedNoteAttachment: vi.fn().mockResolvedValue(undefined),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));
vi.mock('@/lib/local/notesLocalRepository', () => ({
  putNote: vi.fn().mockResolvedValue(undefined),
  putLocalEdit: vi.fn().mockResolvedValue(undefined),
  deleteNote: vi.fn().mockResolvedValue(undefined),
  getOwnerMeta: vi.fn().mockResolvedValue({ lastRemoteRevision: 0 }),
  listPendingPushes: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/lib/notes/tombstones', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/notes/tombstones')>()),
  restoreCloudNote: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('@/lib/attachments/supabaseAttachmentMetadata', () => metadataMocks);

import {
  resetAttachmentBlobStoreForTests,
  setAttachmentBlobStoreForTests,
} from '@/lib/attachments/attachmentBlobStoreRegistry';
import {
  resetDeferredAttachmentGcForTests,
  retryPendingAttachmentGc,
} from '@/lib/attachments/attachmentSyncService';
import { removeNotes } from '@/lib/notes/noteActions';
import { reportPermanentDelete } from '@/screens/main/useNoteActions';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

function noteWithImage(): Note {
  return createEmptyNote({
    id: 'note-1',
    localId: 1,
    timestamp: 1,
    title: 'Holiday',
    content: 'Photos',
    attachments: [
      { id: 'att-1', noteId: 1, storagePath: 'r2:owners/u/notes/note-1/att-1', type: 'image' },
    ],
  });
}

/** Lets the fire-and-forget Undo and cleanup promises settle. */
async function settle(): Promise<void> {
  for (let i = 0; i < 10; i += 1) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * AUDIT-003: a permanent delete used to delete the note's images before its Undo was even shown.
 * Deleting an image claims it, and restore_note keeps claimed images deleted, so Undo brought the
 * note back without them.
 */
describe('Undo after a permanent delete keeps the images (AUDIT-003)', () => {
  const blobDelete = vi.fn().mockResolvedValue(undefined);

  beforeEach(async () => {
    vi.clearAllMocks();
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'http://127.0.0.1:8787');
    setAttachmentBlobStoreForTests({ upload: vi.fn(), download: vi.fn(), delete: blobDelete });
    resetDeferredAttachmentGcForTests();
    useToastStore.getState().dismiss();
    useTombstoneStore.getState().reset();
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    const note = noteWithImage();
    useNotesStore.getState().setNotes([note]);
  });

  afterEach(() => {
    resetAttachmentBlobStoreForTests();
    vi.unstubAllEnvs();
  });

  async function deleteForever(): Promise<Note> {
    const snapshot = { ...noteWithImage() };
    const result = await removeNotes([snapshot.id], { deferAttachmentGc: true });
    reportPermanentDelete(result, [snapshot], () => 'Note deleted permanently');
    return snapshot;
  }

  it('does not delete the images while Undo is offered', async () => {
    await deleteForever();

    expect(useToastStore.getState().message?.actionLabel).toBe('Undo');
    expect(blobDelete).not.toHaveBeenCalled();
  });

  it('never deletes them when Undo is pressed', async () => {
    await deleteForever();

    useToastStore.getState().message!.onAction!();
    useToastStore.getState().dismiss();
    await settle();
    // A sync after the Undo must not finish a cleanup that was cancelled.
    await retryPendingAttachmentGc();

    expect(blobDelete).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes.find((n) => n.id === 'note-1')?.attachments).toHaveLength(1);
  });

  it('keeps sync from deleting them while Undo is still offered', async () => {
    await deleteForever();
    // The server reports the note's images as deleted with it, as delete_note leaves them.
    metadataMocks.listPendingDeletedAttachments.mockResolvedValueOnce([
      { attachmentId: 'att-1', noteId: 'note-1', objectKey: 'owners/u/notes/note-1/att-1' },
    ]);

    await retryPendingAttachmentGc();

    expect(blobDelete).not.toHaveBeenCalled();
  });

  it('deletes them once the Undo closes without being pressed', async () => {
    await deleteForever();

    useToastStore.getState().dismiss();
    await settle();

    expect(blobDelete).toHaveBeenCalledWith('note-1', 'att-1', 'user-1');
    expect(useTombstoneStore.getState().pendingAttachmentGcEntries()).toEqual([]);
  });

  it('deletes them when the next toast replaces the Undo', async () => {
    await deleteForever();

    useToastStore.getState().show('Something else happened');
    await settle();

    expect(blobDelete).toHaveBeenCalledWith('note-1', 'att-1', 'user-1');
  });

  it('still deletes straight away when there is no Undo', async () => {
    await removeNotes(['note-1']);

    expect(blobDelete).toHaveBeenCalledWith('note-1', 'att-1', 'user-1');
  });
});
