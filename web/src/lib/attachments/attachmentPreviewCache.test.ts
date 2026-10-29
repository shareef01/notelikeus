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
  clearAttachmentPreviewCache,
  peekAttachmentPreviewUrl,
  releaseAttachmentPreview,
  resolveAttachmentPreviewUrl,
  retainAttachmentPreview,
  revokeAttachmentPreviewUrl,
} from '@/lib/attachments/attachmentPreviewCache';

/** A promise that a test settles by hand. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const remoteAttachment = (id: string): Attachment => ({
  id,
  noteId: 1,
  storagePath: `r2:owners/o/notes/n/${id}`,
  type: 'image',
});

describe('attachmentPreviewCache', () => {
  let nextUrl = 0;
  let createObjectURL: ReturnType<typeof vi.fn>;
  let revokeObjectURL: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    nextUrl = 0;
    createObjectURL = vi.fn(() => `blob:preview-${(nextUrl += 1)}`);
    revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }));
    r2Enabled.mockReturnValue(true);
    peekPending.mockReset();
    getPendingBlob.mockReset();
    download.mockReset();
    download.mockResolvedValue(new Blob(['x']));
  });

  afterEach(() => {
    clearAttachmentPreviewCache();
    vi.unstubAllGlobals();
  });

  it('keeps a preview after its editor closes, so reopening the note downloads nothing', async () => {
    retainAttachmentPreview('n', 'a');
    const first = await resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    releaseAttachmentPreview('n', 'a');

    expect(peekAttachmentPreviewUrl('n', 'a')).toBe(first);
    expect(await resolveAttachmentPreviewUrl('n', remoteAttachment('a'))).toBe(first);
    expect(download).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).not.toHaveBeenCalled();
  });

  it('shares one download between callers asking at the same time', async () => {
    const pending = deferred<Blob>();
    download.mockReturnValueOnce(pending.promise);

    const first = resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    const second = resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    pending.resolve(new Blob(['x']));

    expect(await first).toBe(await second);
    expect(download).toHaveBeenCalledTimes(1);
    expect(createObjectURL).toHaveBeenCalledTimes(1);
  });

  it('evicts the least recently used preview, but never one an editor is showing', async () => {
    retainAttachmentPreview('n', 'shown');
    const shown = await resolveAttachmentPreviewUrl('n', remoteAttachment('shown'));
    const oldest = await resolveAttachmentPreviewUrl('n', remoteAttachment('old'));
    for (let i = 0; i < 11; i += 1) {
      await resolveAttachmentPreviewUrl('n', remoteAttachment(`a${i}`));
    }

    expect(revokeObjectURL).toHaveBeenCalledWith(oldest);
    expect(revokeObjectURL).not.toHaveBeenCalledWith(shown);
    expect(peekAttachmentPreviewUrl('n', 'shown')).toBe(shown);
  });

  it('does not keep a picture that finished loading after the session was cleared', async () => {
    const pending = deferred<Blob>();
    download.mockReturnValueOnce(pending.promise);

    const loading = resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    clearAttachmentPreviewCache();
    pending.resolve(new Blob(['x']));

    expect(await loading).toBeNull();
    expect(peekAttachmentPreviewUrl('n', 'a')).toBeNull();
  });

  it('loads afresh for a request made after a clear, while the old load is still running', async () => {
    const stale = deferred<Blob>();
    download.mockReturnValueOnce(stale.promise);

    const before = resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    clearAttachmentPreviewCache();
    // Signed back in and reopened the note before the old download finished.
    const after = resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    stale.resolve(new Blob(['x']));

    expect(await before).toBeNull();
    expect(await after).toBe('blob:preview-1');
    expect(download).toHaveBeenCalledTimes(2);
    expect(peekAttachmentPreviewUrl('n', 'a')).toBe('blob:preview-1');
  });

  it('revokes every preview on clear', async () => {
    const url = await resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    clearAttachmentPreviewCache();

    expect(revokeObjectURL).toHaveBeenCalledWith(url);
    expect(peekAttachmentPreviewUrl('n', 'a')).toBeNull();
  });

  it('forgets a removed attachment', async () => {
    const url = await resolveAttachmentPreviewUrl('n', remoteAttachment('a'));
    revokeAttachmentPreviewUrl('n', 'a');

    expect(revokeObjectURL).toHaveBeenCalledWith(url);
    expect(peekAttachmentPreviewUrl('n', 'a')).toBeNull();
  });

  it('returns null when the download fails, and tries again next time', async () => {
    download.mockRejectedValueOnce(new Error('offline'));

    expect(await resolveAttachmentPreviewUrl('n', remoteAttachment('a'))).toBeNull();
    expect(await resolveAttachmentPreviewUrl('n', remoteAttachment('a'))).toBe('blob:preview-1');
  });
});
