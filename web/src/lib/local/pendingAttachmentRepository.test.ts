import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { looksSealed } from '@/lib/crypto/attachmentBytesCodec';
import { resetAttachmentCryptoKeyForTests } from '@/lib/crypto/attachmentCryptoKey';
import { PENDING_ATTACHMENTS_STORE } from '@/lib/local/constants';
import {
  closeNotesDatabaseForTests,
  resetNotesDatabaseForTests,
  withStore,
} from '@/lib/local/idb';
import {
  clearPendingAttachmentsForOwner,
  deletePendingAttachment,
  findPendingAttachmentById,
  getPendingAttachment,
  listPendingAttachmentsForOwner,
  putPendingAttachment,
} from '@/lib/local/pendingAttachmentRepository';

describe('pendingAttachmentRepository', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    await resetAttachmentCryptoKeyForTests();
  });

  it('stores and retrieves pending attachment blobs', async () => {
    const blob = new Blob(['sample-content'], { type: 'image/png' });
    await putPendingAttachment({
      ownerId: 'user-1',
      noteId: 'note-1',
      attachmentId: 'att-1',
      blob,
      mimeType: 'image/png',
      sizeBytes: blob.size,
      createdAt: 1000,
    });

    const retrieved = await getPendingAttachment('user-1', 'note-1', 'att-1');
    expect(retrieved).toBeDefined();
    expect(retrieved?.attachmentId).toBe('att-1');
    expect(retrieved?.mimeType).toBe('image/png');
    expect(retrieved?.sizeBytes).toBe(blob.size);
    expect(retrieved?.blob).toBeDefined();

    const foundById = await findPendingAttachmentById('att-1');
    expect(foundById).toBeDefined();
    expect(foundById?.ownerId).toBe('user-1');
  });

  it('survives database reconstruction and process restart', async () => {
    const blob = new Blob(['durable-bytes'], { type: 'image/jpeg' });
    await putPendingAttachment({
      ownerId: 'user-durable',
      noteId: 'note-durable',
      attachmentId: 'att-durable',
      blob,
      mimeType: 'image/jpeg',
      sizeBytes: blob.size,
      createdAt: 2000,
    });

    // Simulate restart by closing active connections without wiping the database
    await closeNotesDatabaseForTests();

    const restored = await getPendingAttachment('user-durable', 'note-durable', 'att-durable');
    expect(restored).toBeDefined();
    expect(restored?.mimeType).toBe('image/jpeg');
    expect(restored?.sizeBytes).toBe(blob.size);
    expect(restored?.blob).toBeDefined();
  });

  it('isolates pending attachments by owner and clears only intended account', async () => {
    const blobA = new Blob(['user-a data']);
    const blobB = new Blob(['user-b data']);

    await putPendingAttachment({
      ownerId: 'user-a',
      noteId: 'note-a',
      attachmentId: 'att-a',
      blob: blobA,
      mimeType: 'text/plain',
      sizeBytes: blobA.size,
      createdAt: 1,
    });

    await putPendingAttachment({
      ownerId: 'user-b',
      noteId: 'note-b',
      attachmentId: 'att-b',
      blob: blobB,
      mimeType: 'text/plain',
      sizeBytes: blobB.size,
      createdAt: 2,
    });

    expect(await listPendingAttachmentsForOwner('user-a')).toHaveLength(1);
    expect(await listPendingAttachmentsForOwner('user-b')).toHaveLength(1);

    await clearPendingAttachmentsForOwner('user-a');

    expect(await listPendingAttachmentsForOwner('user-a')).toHaveLength(0);
    expect(await listPendingAttachmentsForOwner('user-b')).toHaveLength(1);
    expect(await getPendingAttachment('user-b', 'note-b', 'att-b')).toBeDefined();
  });

  it('deletes individual pending attachments cleanly', async () => {
    const blob = new Blob(['to-delete']);
    await putPendingAttachment({
      ownerId: 'user-del',
      noteId: 'note-del',
      attachmentId: 'att-del',
      blob,
      mimeType: 'text/plain',
      sizeBytes: blob.size,
      createdAt: 1,
    });

    await deletePendingAttachment('user-del', 'note-del', 'att-del');
    expect(await getPendingAttachment('user-del', 'note-del', 'att-del')).toBeNull();
  });

  it('seals bytes at rest in IndexedDB while returning plaintext to callers', async () => {
    const blob = new Blob(['secret-bytes'], { type: 'image/png' });
    await putPendingAttachment({
      ownerId: 'user-seal',
      noteId: 'note-seal',
      attachmentId: 'att-seal',
      blob,
      mimeType: 'image/png',
      sizeBytes: blob.size,
      createdAt: 1,
    });

    const raw = await withStore<{ blob: Blob | ArrayBuffer } | undefined>(
      PENDING_ATTACHMENTS_STORE,
      'readonly',
      (store) => store.get(['user-seal', 'note-seal', 'att-seal']),
    );
    expect(raw?.blob).toBeDefined();
    const rawBytes =
      raw!.blob instanceof ArrayBuffer
        ? new Uint8Array(raw!.blob)
        : new Uint8Array(await raw!.blob.arrayBuffer());
    expect(looksSealed(rawBytes)).toBe(true);
    expect(new TextDecoder().decode(rawBytes)).not.toContain('secret-bytes');

    const retrieved = await getPendingAttachment('user-seal', 'note-seal', 'att-seal');
    expect(await retrieved?.blob.text()).toBe('secret-bytes');
    expect(retrieved?.sizeBytes).toBe(blob.size);
  });

  it('dual-reads legacy plaintext and migrates it to sealed form', async () => {
    const plain = new TextEncoder().encode('legacy-plain');
    const legacy = {
      ownerId: 'user-legacy',
      noteId: 'note-legacy',
      attachmentId: 'att-legacy',
      // Pre-sealing rows held plaintext bytes (Blob or ArrayBuffer). ArrayBuffer survives
      // IndexedDB structured clone reliably in this test environment.
      blob: plain.buffer.slice(plain.byteOffset, plain.byteOffset + plain.byteLength),
      mimeType: 'text/plain',
      sizeBytes: plain.byteLength,
      createdAt: 1,
    };
    await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => {
      store.put(legacy);
    });

    const retrieved = await getPendingAttachment('user-legacy', 'note-legacy', 'att-legacy');
    expect(await retrieved?.blob.text()).toBe('legacy-plain');

    let sealed = false;
    for (let i = 0; i < 40; i++) {
      const raw = await withStore<{ blob: Blob | ArrayBuffer } | undefined>(
        PENDING_ATTACHMENTS_STORE,
        'readonly',
        (store) => store.get(['user-legacy', 'note-legacy', 'att-legacy']),
      );
      const payload = raw!.blob;
      expect(payload).toBeInstanceOf(ArrayBuffer);
      const rawBytes = new Uint8Array(payload as ArrayBuffer);
      if (looksSealed(rawBytes)) {
        sealed = true;
        break;
      }
      await new Promise((r) => setTimeout(r, 25));
    }
    expect(sealed).toBe(true);

    const again = await getPendingAttachment('user-legacy', 'note-legacy', 'att-legacy');
    expect(await again?.blob.text()).toBe('legacy-plain');
  });
});
