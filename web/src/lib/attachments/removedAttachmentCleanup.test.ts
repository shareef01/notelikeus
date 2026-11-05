import { beforeEach, expect, it, vi } from 'vitest';
import { retryRemovedAttachmentCleanup } from './removedAttachmentCleanup';
import { setAttachmentBlobStoreForTests } from './attachmentBlobStoreRegistry';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { dropPendingPush, getOwnerMeta, putLocalEdit } from '@/lib/local/notesLocalRepository';
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
  expect((await getOwnerMeta('A'))?.pendingAttachmentRemovals?.['1']).toEqual([]);
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
