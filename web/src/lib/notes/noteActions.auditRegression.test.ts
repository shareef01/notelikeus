import { beforeEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  put: vi.fn(), del: vi.fn(), cleanup: vi.fn(), upload: vi.fn(),
  upsert: vi.fn(), remove: vi.fn(),
}));
vi.mock('@/lib/local/notesLocalRepository', () => ({
  putNote: mocks.put, putLocalEdit: mocks.put, deleteNote: mocks.del,
  getOwnerMeta: vi.fn(async () => ({ lastRemoteRevision: 0 })),
}));
vi.mock('@/lib/attachments/attachmentConfig', () => ({ isR2AttachmentsEnabled: () => true }));
vi.mock('@/lib/attachments/attachmentSyncService', () => ({
  deleteAttachmentsForNote: mocks.cleanup, syncNoteAttachments: mocks.upload,
  releaseUploadedPendingAttachments: vi.fn(), AttachmentSyncIncompleteError: class extends Error {},
}));
vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => ({ upsertNote: mocks.upsert, deleteNote: mocks.remove }),
}));
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { useNotesStore } from '@/store/notesStore';
import { useAuthStore } from '@/store/authStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';
import { restorePendingDeletions } from '@/lib/notes/pendingDeletionStash';
const owner = (uid: string) => useAuthStore.getState().setUser({ uid, email: null, displayName: null });
const original = () => createEmptyNote({ id: '1', localId: 1, content: 'original', attachments: [
  { id: 'image', noteId: 1, storagePath: 'r2:owners/A/notes/1/image', type: 'image' },
] });
beforeEach(() => {
  vi.resetAllMocks();
  mocks.put.mockResolvedValue(undefined); mocks.del.mockResolvedValue(undefined);
  mocks.cleanup.mockResolvedValue(undefined); mocks.upsert.mockResolvedValue(undefined);
  mocks.upload.mockImplementation(async n => n);
  useNotesStore.getState().reset(); useAuthStore.getState().reset(); useTombstoneStore.getState().reset();
  owner('A'); useNotesStore.getState().setNotes([original()]);
});

// Regression tests for persistence failures and account switches.
it('AUD-E02 keeps image bytes when local persistence fails', async () => {
  mocks.put.mockRejectedValueOnce(new Error('quota exceeded'));
  await expect(saveNote({ ...original(), attachments: [] })).rejects.toThrow('quota exceeded');
  expect(mocks.cleanup).not.toHaveBeenCalled();
  expect(useNotesStore.getState().notes[0]?.attachments).toHaveLength(1);
});

it('AUD-E03 never publishes or uploads A text to B after a delayed write', async () => {
  let finish!: () => void;
  mocks.put.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; }));
  const saving = saveNote({ ...original(), attachments: [], content: 'A secret' });
  await vi.waitFor(() => expect(mocks.put).toHaveBeenCalled());
  owner('B'); useNotesStore.getState().reset(); finish(); await saving;
  expect(mocks.put.mock.calls.every(([target]) => target === 'A')).toBe(true);
  expect(mocks.upsert).not.toHaveBeenCalled();
});

it('AUD-E04 keeps a late deletion out of B tombstones and UI', async () => {
  let finish!: () => void;
  mocks.del.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; }));
  useNotesStore.getState().setNotes([{ ...original(), attachments: [] }]);
  const deleting = removeNote('1');
  await vi.waitFor(() => expect(mocks.del).toHaveBeenCalled());
  owner('B'); useTombstoneStore.getState().reset();
  useNotesStore.getState().setNotes([createEmptyNote({ id: '1', localId: 1, content: 'B words' })]);
  finish(); await deleting;
  expect(useNotesStore.getState().notes[0]?.content).toBe('B words');
  expect(useTombstoneStore.getState().isDeleted('1')).toBe(false);
  owner('A');
  restorePendingDeletions('A');
  expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
});
