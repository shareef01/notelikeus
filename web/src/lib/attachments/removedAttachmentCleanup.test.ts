import { beforeEach, expect, it, vi } from 'vitest';
import { retryRemovedAttachmentCleanup } from './removedAttachmentCleanup';
import { setAttachmentBlobStoreForTests } from './attachmentBlobStoreRegistry';
import { NOTES_STORE } from '@/lib/local/constants';
import { resetNotesDatabaseForTests, withStore } from '@/lib/local/idb';
import { dropPendingPush, getOwnerMeta, listNotes, putLocalEdit } from '@/lib/local/notesLocalRepository';
import { useAuthStore } from '@/store/authStore';
import { createEmptyNote } from '@/types/note';

const removed = { id: 'image', noteId: 1, type: 'image', storagePath: 'r2:old-image' };
const note = () => createEmptyNote({ id: '1', localId: 1, content: 'kept text' });
const del = vi.fn();
beforeEach(async () => {
  await resetNotesDatabaseForTests();
  useAuthStore.getState().setUser({ uid: 'A', email: null, displayName: null });
  del.mockReset().mockResolvedValue(undefined);
  setAttachmentBlobStoreForTests({ upload: vi.fn(), download: vi.fn(), delete: del });
});

it('keeps removed images until the replacement note is confirmed, then retries failed cleanup', async () => {
  await putLocalEdit('A', note(), [removed]);
  await retryRemovedAttachmentCleanup('A');
  expect(del).not.toHaveBeenCalled();
  await dropPendingPush('A', '1');
  del.mockRejectedValueOnce(new Error('offline'));
  await retryRemovedAttachmentCleanup('A');
  expect((await getOwnerMeta('A'))?.pendingAttachmentRemovals?.['1']).toHaveLength(1);
  await retryRemovedAttachmentCleanup('A');
  expect(del).toHaveBeenLastCalledWith('1', 'image', 'A');
  expect((await getOwnerMeta('A'))?.pendingAttachmentRemovals).toEqual({});
});

it('AUD-G03 reads no notes when nothing is waiting, and leaves no empty entries behind', async () => {
  await putLocalEdit('A', note());
  await dropPendingPush('A', '1');
  expect((await getOwnerMeta('A'))?.pendingAttachmentRemovals).toEqual({});
  // The library is read through the notes store's owner index.
  const libraryRead = vi.spyOn(IDBObjectStore.prototype, 'index');
  try {
    await retryRemovedAttachmentCleanup('A');
    expect(libraryRead).not.toHaveBeenCalled();
    expect(del).not.toHaveBeenCalled();
    // The same probe does see a read once an image is waiting.
    await putLocalEdit('A', { ...note(), timestamp: 2 }, [removed]);
    await dropPendingPush('A', '1');
    libraryRead.mockClear();
    await retryRemovedAttachmentCleanup('A');
    expect(libraryRead).toHaveBeenCalled();
    expect(del).toHaveBeenCalledWith('1', 'image', 'A');
  } finally {
    libraryRead.mockRestore();
  }
});

it('AUD-G03 an unreadable note does not stop cleanup for the others', async () => {
  await putLocalEdit('A', note(), [removed]);
  await dropPendingPush('A', '1');
  await withStore(NOTES_STORE, 'readwrite', (store) =>
    // A sealed body that is not one: this row cannot be opened.
    store.put({ ownerId: 'A', id: '2', note: { ...createEmptyNote({ id: '2', localId: 2 }), sealedBody: new ArrayBuffer(4) } }),
  );
  await expect(listNotes('A')).rejects.toThrow();
  await retryRemovedAttachmentCleanup('A');
  expect(del).toHaveBeenCalledWith('1', 'image', 'A');
});

it('cancels cleanup when a later local edit retains the image', async () => {
  await putLocalEdit('A', note(), [removed]);
  await putLocalEdit('A', { ...note(), attachments: [removed] });
  await dropPendingPush('A', '1');
  await retryRemovedAttachmentCleanup('A');
  expect(del).not.toHaveBeenCalled();
});

it('never cleans an inactive owner through the current account', async () => {
  await putLocalEdit('A', note(), [removed]);
  await dropPendingPush('A', '1');
  useAuthStore.getState().setUser({ uid: 'B', email: null, displayName: null });
  await retryRemovedAttachmentCleanup('A');
  expect(del).not.toHaveBeenCalled();
});
