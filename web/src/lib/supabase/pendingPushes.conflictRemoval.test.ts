import { beforeEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ apply: vi.fn() }));
vi.mock('@/lib/attachments/attachmentConfig', () => ({ isR2AttachmentsEnabled: () => true }));
vi.mock('@/lib/supabase/supabaseSyncEngine', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/supabase/supabaseSyncEngine')>(),
  applyNoteChange: mocks.apply,
}));

import { setAttachmentBlobStoreForTests } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { getOwnerMeta, listNotes, putLocalEdit, putNote } from '@/lib/local/notesLocalRepository';
import { flushPendingPushes } from '@/lib/supabase/pendingPushes';
import { NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';
import { useNotesStore } from '@/store/notesStore';
import { useAuthStore } from '@/store/authStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

const owner = 'A';
const image = { id: 'image', noteId: 1, type: 'image' as const, storagePath: 'r2:owners/A/1/image' };
const del = vi.fn();

beforeEach(async () => {
  vi.resetAllMocks();
  await resetNotesDatabaseForTests();
  useNotesStore.getState().reset();
  useAuthStore.getState().setUser({ uid: owner, email: null, displayName: null });
  useTombstoneStore.getState().reset();
  del.mockResolvedValue(undefined);
  setAttachmentBlobStoreForTests({ upload: vi.fn(), download: vi.fn(), delete: del });
});

async function removeImageLocally() {
  const withImage = createEmptyNote({ id: '1', localId: 1, content: 'shared text', attachments: [image] });
  await putNote(owner, withImage);
  const withoutImage = { ...withImage, content: 'edited here, image removed', attachments: [], timestamp: 2000 };
  await putLocalEdit(owner, withoutImage, [image]);
  useNotesStore.getState().setNotes([withoutImage]);
}

it('AUD-G01 keeps the image of the cloud version when the edit that removed it is refused', async () => {
  await removeImageLocally();
  // Another device edited the note first and kept the image. The refusal carries the cloud's
  // version, and that payload has no attachment list.
  const cloud = createEmptyNote({ id: '1', localId: 1, content: 'edited on the other device', timestamp: 1500 });
  mocks.apply.mockImplementation(async (_owner: string, note: { id: string }) => {
    if (note.id === '1') throw new NoteConflictError('1', cloud, 'Revision conflict');
    return note;
  });

  await flushPendingPushes(owner);

  expect((await listNotes(owner)).find((note) => note.id === '1')?.content).toBe('edited on the other device');
  expect(del).not.toHaveBeenCalled();
  expect((await getOwnerMeta(owner))?.pendingAttachmentRemovals).toEqual({});
  // And nothing is left to delete it on a later flush.
  await flushPendingPushes(owner);
  expect(del).not.toHaveBeenCalled();
});

it('AUD-G01 still deletes the removed image once the edit is accepted', async () => {
  await removeImageLocally();
  mocks.apply.mockImplementation(async (_owner: string, note: unknown) => note);

  await flushPendingPushes(owner);

  expect(del).toHaveBeenCalledWith('1', 'image', owner);
  expect((await getOwnerMeta(owner))?.pendingAttachmentRemovals).toEqual({});
});
