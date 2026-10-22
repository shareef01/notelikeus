import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Attachment } from '@/types/attachment';

const peekPending = vi.fn();
const getPendingBlob = vi.fn();
const download = vi.fn();
const r2Enabled = vi.fn();

vi.mock('@/lib/attachments/pendingAttachmentStore', () => ({
  peekPendingAttachment: (...args: unknown[]) => peekPending(...args),
  getPendingAttachmentBlob: (...args: unknown[]) => getPendingBlob(...args),
}));
vi.mock('@/lib/attachments/attachmentConfig', () => ({
  isR2AttachmentsEnabled: () => r2Enabled(),
}));
vi.mock('@/lib/attachments/attachmentBlobStoreRegistry', () => ({
  getAttachmentBlobStore: () => ({ download }),
}));

import {
  clearAttachmentThumbnailCache,
  getAttachmentThumbnailActiveRefCount,
  peekAttachmentThumbnailUrl,
  releaseAttachmentThumbnail,
  resolveAttachmentThumbnailUrl,
  retainAttachmentThumbnail,
} from '@/lib/attachments/attachmentThumbnailCache';

/** A promise that a test settles by hand. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const pendingAttachment = (id: string): Attachment => ({
  id,
  noteId: 1,
  storagePath: `pending:${id}`,
  type: 'image',
});
const remoteAttachment = (id: string): Attachment => ({
  id,
  noteId: 1,
  storagePath: `r2:owners/o/notes/n/${id}`,
  type: 'image',
});

describe('attachmentThumbnailCache', () => {
  let nextUrl = 0;
  let createObjectURL: ReturnType<typeof vi.fn>;
  let revokeObjectURL: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    nextUrl = 0;
    createObjectURL = vi.fn(() => `blob:thumb-${(nextUrl += 1)}`);
    revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }));
    r2Enabled.mockReturnValue(true);
    peekPending.mockReset();
    getPendingBlob.mockReset();
    download.mockReset();
  });

  afterEach(() => {
    clearAttachmentThumbnailCache();
    vi.unstubAllGlobals();
  });

  it('makes a thumbnail from a staged attachment and serves it from memory afterwards', async () => {
    peekPending.mockReturnValue({ blob: new Blob(['x'], { type: 'image/png' }), mimeType: 'image/png' });

    const first = await resolveAttachmentThumbnailUrl('n1', pendingAttachment('a1'));
    const second = await resolveAttachmentThumbnailUrl('n1', pendingAttachment('a1'));

    expect(first).toBe('blob:thumb-1');
    expect(second).toBe(first);
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(download).not.toHaveBeenCalled();
    expect(peekAttachmentThumbnailUrl('n1', 'a1')).toBe(first);
  });

  it('downloads a remote attachment once however many cards ask at the same time', async () => {
    download.mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' }));

    const urls = await Promise.all([
      resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1')),
      resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1')),
      resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1')),
    ]);

    expect(download).toHaveBeenCalledTimes(1);
    expect(new Set(urls).size).toBe(1);
  });

  it('keeps thumbnails of different notes apart', async () => {
    download.mockResolvedValue(new Blob(['x']));

    const a = await resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1'));
    const b = await resolveAttachmentThumbnailUrl('n2', remoteAttachment('a1'));

    expect(a).not.toBe(b);
    expect(download).toHaveBeenCalledTimes(2);
  });

  it('has no picture for a staged attachment whose bytes are gone, and does not keep asking', async () => {
    peekPending.mockReturnValue(undefined);
    getPendingBlob.mockResolvedValue(undefined);

    expect(await resolveAttachmentThumbnailUrl('n1', pendingAttachment('gone'))).toBeNull();
    expect(await resolveAttachmentThumbnailUrl('n1', pendingAttachment('gone'))).toBeNull();

    expect(getPendingBlob).toHaveBeenCalledTimes(1);
  });

  it('has no picture when the download fails', async () => {
    download.mockRejectedValue(new Error('offline'));

    expect(await resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1'))).toBeNull();
  });

  it('does not touch the network when remote attachments are not configured', async () => {
    r2Enabled.mockReturnValue(false);

    expect(await resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1'))).toBeNull();
    expect(download).not.toHaveBeenCalled();
  });

  it('never has more than three downloads in flight', async () => {
    let active = 0;
    let peak = 0;
    const releases: Array<() => void> = [];
    download.mockImplementation(async () => {
      active += 1;
      peak = Math.max(peak, active);
      await new Promise<void>((resolve) => releases.push(resolve));
      active -= 1;
      return new Blob(['x']);
    });

    const all = Promise.all(
      ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => resolveAttachmentThumbnailUrl('n1', remoteAttachment(id))),
    );
    // Release whatever has started, round after round: three at a time can run, so six take two rounds.
    for (let round = 0; round < 10; round += 1) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      for (const release of releases.splice(0)) release();
    }
    const urls = await all;

    expect(peak).toBeLessThanOrEqual(3);
    expect(urls.every((url) => url !== null)).toBe(true);
  });

  it('revokes every thumbnail when cleared', async () => {
    download.mockResolvedValue(new Blob(['x']));
    await resolveAttachmentThumbnailUrl('n1', remoteAttachment('a1'));
    await resolveAttachmentThumbnailUrl('n1', remoteAttachment('a2'));

    clearAttachmentThumbnailCache();

    expect(revokeObjectURL).toHaveBeenCalledTimes(2);
    expect(peekAttachmentThumbnailUrl('n1', 'a1')).toBeNull();
  });

  it('does not cache a picture that finished loading after the data was cleared', async () => {
    const held = deferred<Blob>();
    download.mockReturnValue(held.promise);

    const pending = resolveAttachmentThumbnailUrl('n1', remoteAttachment('late'));
    clearAttachmentThumbnailCache();
    held.resolve(new Blob(['x']));

    expect(await pending).toBeNull();
    expect(peekAttachmentThumbnailUrl('n1', 'late')).toBeNull();
    expect(createObjectURL).not.toHaveBeenCalled();
  });

  describe('lifetime safety and reference counting with >128 image cards', () => {
    it('never revokes Object URLs of actively mounted cards even when exceeding 128 items', async () => {
      download.mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' }));

      // Mount 130 active image cards (each retaining a reference)
      const count = 130;
      for (let i = 0; i < count; i += 1) {
        retainAttachmentThumbnail(`note-${i}`, `att-${i}`);
        await resolveAttachmentThumbnailUrl(`note-${i}`, remoteAttachment(`att-${i}`));
      }

      // All 130 URLs were created and none was revoked because all 130 are actively mounted!
      expect(createObjectURL).toHaveBeenCalledTimes(count);
      expect(revokeObjectURL).not.toHaveBeenCalled();

      // Check ref counts
      expect(getAttachmentThumbnailActiveRefCount('note-0', 'att-0')).toBe(1);
      expect(getAttachmentThumbnailActiveRefCount('note-129', 'att-129')).toBe(1);
    });

    it('evicts only unreferenced URLs when cards scroll away, preserving active cards', async () => {
      download.mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' }));

      // Load 128 cards that are viewed and then scrolled out of view (unmounted/released)
      for (let i = 0; i < 128; i += 1) {
        retainAttachmentThumbnail(`scrolled-${i}`, `att-${i}`);
        await resolveAttachmentThumbnailUrl(`scrolled-${i}`, remoteAttachment(`att-${i}`));
        releaseAttachmentThumbnail(`scrolled-${i}`, `att-${i}`);
      }

      // Zero active refs now
      expect(revokeObjectURL).not.toHaveBeenCalled();

      // Now 5 new active cards mount, pushing total seen past 128 limit
      for (let i = 0; i < 5; i += 1) {
        retainAttachmentThumbnail(`active-${i}`, `att-${i}`);
        await resolveAttachmentThumbnailUrl(`active-${i}`, remoteAttachment(`att-${i}`));
      }

      // Exactly 5 oldest unreferenced URLs were revoked!
      expect(revokeObjectURL).toHaveBeenCalledTimes(5);
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:thumb-1');
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:thumb-2');
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:thumb-3');
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:thumb-4');
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:thumb-5');

      // The new active cards are in cache and not revoked
      expect(peekAttachmentThumbnailUrl('active-0', 'att-0')).toBe('blob:thumb-129');
    });

    it('handles remounting correctly after release and eviction', async () => {
      download.mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' }));

      // Mount card 0, then unmount it
      retainAttachmentThumbnail('note-remount', 'att-0');
      const firstUrl = await resolveAttachmentThumbnailUrl('note-remount', remoteAttachment('att-0'));
      releaseAttachmentThumbnail('note-remount', 'att-0');

      // Fill cache with 128 other items so card 0 gets evicted
      for (let i = 1; i <= 128; i += 1) {
        await resolveAttachmentThumbnailUrl(`filler-${i}`, remoteAttachment(`att-${i}`));
      }

      // card 0 URL was revoked during the eviction
      expect(revokeObjectURL).toHaveBeenCalledWith(firstUrl);
      expect(peekAttachmentThumbnailUrl('note-remount', 'att-0')).toBeNull();

      // Remount card 0: it re-resolves and gets a fresh URL cleanly
      retainAttachmentThumbnail('note-remount', 'att-0');
      const remountUrl = await resolveAttachmentThumbnailUrl('note-remount', remoteAttachment('att-0'));
      expect(remountUrl).not.toBeNull();
      expect(remountUrl).not.toBe(firstUrl);
    });

    it('account switching completely cleans up all Object URLs and active references', async () => {
      download.mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' }));

      // Mount 10 active cards
      for (let i = 0; i < 10; i += 1) {
        retainAttachmentThumbnail(`user1-${i}`, `att-${i}`);
        await resolveAttachmentThumbnailUrl(`user1-${i}`, remoteAttachment(`att-${i}`));
      }

      // User signs out or switches account
      clearAttachmentThumbnailCache();

      // All 10 URLs revoked
      expect(revokeObjectURL).toHaveBeenCalledTimes(10);
      // Active refs reset
      expect(getAttachmentThumbnailActiveRefCount('user1-0', 'att-0')).toBe(0);
      expect(peekAttachmentThumbnailUrl('user1-0', 'att-0')).toBeNull();
    });

    it('maintains bounded memory during continuous scrolling of 200 items', async () => {
      download.mockResolvedValue(new Blob(['x'], { type: 'image/jpeg' }));

      // Simulate a user scrolling down a 200-image list:
      // at any moment, at most 20 cards are in viewport (active refs), older ones unmount.
      for (let i = 0; i < 200; i += 1) {
        retainAttachmentThumbnail(`scroll-${i}`, `att-${i}`);
        await resolveAttachmentThumbnailUrl(`scroll-${i}`, remoteAttachment(`att-${i}`));
        // Cards older than 20 positions scroll out of view
        if (i >= 20) {
          releaseAttachmentThumbnail(`scroll-${i - 20}`, `att-${i - 20}`);
        }
      }

      // Bounded capacity: 200 items loaded, so at least 200 - 128 = 72 evictions must have occurred
      expect(revokeObjectURL.mock.calls.length).toBeGreaterThanOrEqual(72);
    });
  });
});
