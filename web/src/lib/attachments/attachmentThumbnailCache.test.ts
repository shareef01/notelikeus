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
  peekAttachmentThumbnailUrl,
  resolveAttachmentThumbnailUrl,
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
});
