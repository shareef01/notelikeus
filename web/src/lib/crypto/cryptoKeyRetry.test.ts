import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getAttachmentCryptoKey,
  resetAttachmentCryptoKeyForTests,
} from '@/lib/crypto/attachmentCryptoKey';
import { getNotesCryptoKey, resetNotesCryptoKeyForTests } from '@/lib/crypto/notesCryptoKey';

/**
 * F-11: one transient IndexedDB failure while loading a sealing key must not disable sealing for
 * the rest of the tab's life. A failed load is not remembered; the next caller tries again, and
 * gets the same key as everyone else once it loads.
 */
const loaders = [
  { name: 'notes', get: getNotesCryptoKey, reset: resetNotesCryptoKeyForTests },
  { name: 'attachments', get: getAttachmentCryptoKey, reset: resetAttachmentCryptoKeyForTests },
];

describe.each(loaders)('$name sealing key', ({ get, reset }) => {
  beforeEach(async () => {
    await reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('retries after a transient open failure instead of caching it', async () => {
    vi.spyOn(indexedDB, 'open').mockImplementationOnce(() => {
      throw new DOMException('blocked by another tab', 'InvalidStateError');
    });

    expect(await get()).toBeNull();
    const key = await get();

    expect(key).not.toBeNull();
    expect(key!.type).toBe('secret');
  });

  it('keeps returning the key it loaded', async () => {
    const first = await get();
    const second = await get();

    expect(first).not.toBeNull();
    expect(second).toBe(first);
  });
});
