import { beforeEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ upload: vi.fn(), release: vi.fn(), apply: vi.fn() }));
vi.mock('@/lib/attachments/attachmentConfig', () => ({ isR2AttachmentsEnabled: () => true }));
vi.mock('@/lib/attachments/attachmentSyncService', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/attachments/attachmentSyncService')>(),
  syncNoteAttachments: mocks.upload,
  releaseUploadedPendingAttachments: mocks.release,
}));
vi.mock('@/lib/supabase/supabaseSyncEngine', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/supabase/supabaseSyncEngine')>(),
  applyNoteChange: mocks.apply,
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, listPendingPushes, putLocalEdit } from '@/lib/local/notesLocalRepository';
import { flushPendingPushes } from '@/lib/supabase/pendingPushes';
import { useNotesStore } from '@/store/notesStore';
import { useAuthStore } from '@/store/authStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';
import { blockPushesAfterWipe } from '@/lib/supabase/wipeGeneration';

const owner = 'attachment-import-owner';
const original = createEmptyNote({ id: 'imported', localId: 1, attachments: [
  { id: 'image', noteId: 1, storagePath: 'pending:image', type: 'image' },
] });
const uploaded = { ...original, attachments: [{ ...original.attachments[0], storagePath: 'r2:uploaded' }] };

beforeEach(async () => {
  vi.resetAllMocks();
  await resetNotesDatabaseForTests();
  useNotesStore.getState().reset();
  useAuthStore.getState().setUser({ uid: owner, email: null, displayName: null });
  useTombstoneStore.getState().reset();
  await putLocalEdit(owner, original);
  mocks.upload.mockResolvedValue(uploaded);
  mocks.apply.mockImplementation(async (_owner, note) => note);
});

it('uploads queued images before committing and releases bytes only after durable settlement', async () => {
  mocks.apply.mockImplementation(async (_owner, note) => {
    expect(note.attachments).toEqual(uploaded.attachments);
    expect((await listNotes(owner))[0].attachments).toEqual(original.attachments);
    expect(mocks.release).not.toHaveBeenCalled();
    return note;
  });
  mocks.release.mockImplementation(async () => {
    expect((await listNotes(owner))[0].attachments).toEqual(uploaded.attachments);
    expect(await listPendingPushes(owner)).toEqual({});
  });

  await flushPendingPushes(owner);

  expect(mocks.upload).toHaveBeenCalledWith(original, owner);
  expect(mocks.release).toHaveBeenCalledWith(original, uploaded, owner);
});

it('retains the note, pending marker and staged bytes on upload failure, then retries', async () => {
  mocks.upload.mockRejectedValueOnce(new Error('offline'));
  await flushPendingPushes(owner);
  expect(mocks.apply).not.toHaveBeenCalled();
  expect(mocks.release).not.toHaveBeenCalled();
  expect((await listNotes(owner))[0].attachments).toEqual(original.attachments);
  expect(Object.keys(await listPendingPushes(owner))).toEqual([original.id]);

  await flushPendingPushes(owner);
  expect(await listPendingPushes(owner)).toEqual({});
  expect(mocks.release).toHaveBeenCalledOnce();
});

it('retains staged bytes when the note commit fails after uploading', async () => {
  mocks.apply.mockRejectedValueOnce(new Error('connection dropped'));
  await flushPendingPushes(owner);
  expect(mocks.release).not.toHaveBeenCalled();
  expect(Object.keys(await listPendingPushes(owner))).toEqual([original.id]);
  expect((await listNotes(owner))[0].attachments).toEqual(original.attachments);
});

it('abandons a queued old-owner push after switching accounts', async () => {
  useAuthStore.getState().setUser({ uid: 'other-owner', email: null, displayName: null });
  await flushPendingPushes(owner);
  expect(mocks.upload).not.toHaveBeenCalled();
  expect(mocks.apply).not.toHaveBeenCalled();
  expect(Object.keys(await listPendingPushes(owner))).toEqual([original.id]);
});

it('keeps the pending edit and never publishes a response received after account switch', async () => {
  const otherNote = createEmptyNote({ id: 'other-note', localId: 2, title: 'Other account' });
  mocks.apply.mockImplementation(async () => {
    useAuthStore.getState().setUser({ uid: 'other-owner', email: null, displayName: null });
    useNotesStore.getState().setNotes([otherNote]);
    return uploaded;
  });
  await flushPendingPushes(owner);
  expect(useNotesStore.getState().notes).toEqual([otherNote]);
  expect(Object.keys(await listPendingPushes(owner))).toEqual([original.id]);
  expect(mocks.release).not.toHaveBeenCalled();
});

it('does not send the note after the account switches during image upload', async () => {
  mocks.upload.mockImplementation(async () => {
    useAuthStore.getState().setUser({ uid: 'other-owner', email: null, displayName: null });
    return uploaded;
  });
  await flushPendingPushes(owner);
  expect(mocks.apply).not.toHaveBeenCalled();
  expect(Object.keys(await listPendingPushes(owner))).toEqual([original.id]);
  expect(mocks.release).not.toHaveBeenCalled();
});

it('does not upload old image bytes while a cloud wipe awaits user confirmation', async () => {
  await blockPushesAfterWipe(owner);
  await flushPendingPushes(owner);
  expect(mocks.upload).not.toHaveBeenCalled();
  expect(mocks.apply).not.toHaveBeenCalled();
  expect(mocks.release).not.toHaveBeenCalled();
  expect(Object.keys(await listPendingPushes(owner))).toEqual([original.id]);
});
