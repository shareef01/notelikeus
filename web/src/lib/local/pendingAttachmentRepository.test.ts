import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { closeNotesDatabaseForTests, resetNotesDatabaseForTests } from '@/lib/local/idb';
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
});
