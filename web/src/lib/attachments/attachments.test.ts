import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  buildAttachmentObjectKey,
  isAttachmentObjectKeyForOwner,
  parseAttachmentObjectKey,
} from '@/lib/attachments/attachmentObjectKey';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import {
  getAttachmentBlobStore,
  resetAttachmentBlobStoreForTests,
  setAttachmentBlobStoreForTests,
} from '@/lib/attachments/attachmentBlobStoreRegistry';
import { noopAttachmentBlobStore } from '@/lib/attachments/noopAttachmentBlobStore';

describe('attachmentObjectKey', () => {
  const ownerId = '11111111-2222-4333-8444-555555555555';

  it('builds owner-scoped keys', () => {
    expect(buildAttachmentObjectKey(ownerId, 'note-1', 'att-1')).toBe(
      `owners/${ownerId}/notes/note-1/att-1`,
    );
  });

  it('validates keys for the owner namespace', () => {
    const key = buildAttachmentObjectKey(ownerId, 'note-1', 'att-1');
    expect(isAttachmentObjectKeyForOwner(key, ownerId)).toBe(true);
    expect(isAttachmentObjectKeyForOwner(key, 'other')).toBe(false);
  });

  it('parses owner/note/attachment segments', () => {
    const key = buildAttachmentObjectKey(ownerId, 'note-1', 'att-1');
    expect(parseAttachmentObjectKey(key)).toEqual({
      ownerId,
      noteId: 'note-1',
      attachmentId: 'att-1',
    });
    expect(parseAttachmentObjectKey('owners/x/notes/bad')).toBeNull();
  });
});

describe('attachmentBlobStoreRegistry', () => {
  afterEach(() => {
    resetAttachmentBlobStoreForTests();
    vi.unstubAllEnvs();
  });

  it('returns noop store when the attachments worker URL is absent', () => {
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', '');
    expect(getAttachmentBlobStore()).toBe(noopAttachmentBlobStore);
  });

  it('allows test override', () => {
    const custom = {
      upload: vi.fn(),
      download: vi.fn(),
      delete: vi.fn(),
    };
    setAttachmentBlobStoreForTests(custom);
    expect(getAttachmentBlobStore()).toBe(custom);
  });
});

describe('isR2AttachmentsEnabled', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('requires a worker url on top of a configured Supabase backend', () => {
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'http://127.0.0.1:8787');
    expect(isR2AttachmentsEnabled()).toBe(true);
  });
});
