import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearPendingAttachmentsForTests,
  getPendingAttachmentBlob,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { useAuthStore } from '@/store/authStore';

/**
 * The cross-client invariant: a note may only reference attachment bytes that outlive the
 * process. On the web that means IndexedDB, and it means the reference is not added at all when
 * the durable write did not happen.
 */
describe('pending attachment durability', () => {
  beforeEach(() => {
    clearPendingAttachmentsForTests();
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: 'user-durable', email: null, displayName: null });
  });

  it('recovers staged bytes after the in-memory cache is gone', async () => {
    const blob = new Blob(['durable bytes'], { type: 'image/png' });
    expect(await storePendingAttachment('att-1', blob, 'image/png', 'note-1')).toBe(true);

    // Stand in for a reload: the process cache dies, IndexedDB does not.
    clearPendingAttachmentsForTests();

    const recovered = await getPendingAttachmentBlob('att-1', 'note-1');
    expect(recovered).toBeDefined();
    expect(recovered?.mimeType).toBe('image/png');
    expect(recovered?.blob).toBeDefined();

    // The stored record itself, so the assertion does not depend on how the test environment
    // structured-clones a Blob.
    const { getPendingAttachment } = await import('@/lib/local/pendingAttachmentRepository');
    const record = await getPendingAttachment('user-durable', 'note-1', 'att-1');
    expect(record?.sizeBytes).toBe(blob.size);
  });

  it('reports failure instead of caching bytes the durable store rejected', async () => {
    const idb = await import('@/lib/local/pendingAttachmentRepository');
    const spy = vi
      .spyOn(idb, 'putPendingAttachment')
      .mockRejectedValueOnce(new Error('quota exceeded'));

    const blob = new Blob(['never staged'], { type: 'image/png' });
    expect(await storePendingAttachment('att-2', blob, 'image/png', 'note-1')).toBe(false);

    // Nothing may be readable, or the caller would reference bytes that are not durable.
    expect(await getPendingAttachmentBlob('att-2', 'note-1')).toBeUndefined();
    spy.mockRestore();
  });

  it('refuses to stage without an owner namespace', async () => {
    useAuthStore.getState().reset();
    const blob = new Blob(['no owner'], { type: 'image/png' });

    expect(await storePendingAttachment('att-3', blob, 'image/png', 'note-1')).toBe(false);
  });

  it('does not surface another account’s staged bytes', async () => {
    const blob = new Blob(['account a bytes'], { type: 'image/png' });
    expect(await storePendingAttachment('att-4', blob, 'image/png', 'note-1')).toBe(true);
    clearPendingAttachmentsForTests();

    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: 'user-other', email: null, displayName: null });

    expect(await getPendingAttachmentBlob('att-4', 'note-1')).toBeUndefined();
  });
});
