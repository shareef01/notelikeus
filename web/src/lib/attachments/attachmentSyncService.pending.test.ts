import { beforeEach, describe, expect, it, vi } from 'vitest';
import 'fake-indexeddb/auto';

vi.mock('@/lib/attachments/attachmentConfig', () => ({
  isR2AttachmentsEnabled: () => true,
  loadAttachmentsWorkerUrl: () => 'https://attachments.test',
}));
import {
  ATTACHMENT_PENDING_PREFIX,
  pendingStoragePath,
} from '@/lib/attachments/attachmentPaths';
import { syncNoteAttachments } from '@/lib/attachments/attachmentSyncService';
import {
  resetAttachmentBlobStoreForTests,
  setAttachmentBlobStoreForTests,
} from '@/lib/attachments/attachmentBlobStoreRegistry';
import {
  clearPendingAttachmentsForTests,
  loadPendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import type { AttachmentBlobStore } from '@/lib/attachments/attachmentBlobStore';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { createEmptyNote } from '@/types/note';
import { useAuthStore } from '@/store/authStore';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('syncNoteAttachments durable pending blobs', () => {
  beforeEach(async () => {
    clearPendingAttachmentsForTests();
    resetAttachmentBlobStoreForTests();
    useAuthStore.getState().reset();
    useAuthStore.getState().enterGuestMode();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('does not consume the blob when upload is deferred and still pending after failure', async () => {
    const gate = deferred<{ objectKey: string; sizeBytes: number; mimeType: string }>();
    const store: AttachmentBlobStore = {
      upload: vi.fn(() => gate.promise),
      download: vi.fn(),
      delete: vi.fn(),
    };
    setAttachmentBlobStoreForTests(store);

    const attachmentId = 'att-1';
    const blob = new Blob(['hello'], { type: 'image/png' });
    await storePendingAttachment(attachmentId, blob, 'image/png', '9');

    const note = {
      ...createEmptyNote({ id: '9', localId: 9 }),
      attachments: [
        {
          id: attachmentId,
          noteId: 9,
          storagePath: pendingStoragePath(attachmentId),
          type: 'image' as const,
          mimeType: 'image/png',
          sizeBytes: 5,
        },
      ],
    };

    const pending = syncNoteAttachments(note);
    expect(await loadPendingAttachment(attachmentId)).toBeDefined();
    gate.reject(new Error('network down'));
    const result = await pending;

    expect(result.pendingCount).toBe(1);
    expect(result.note.attachments[0]?.storagePath.startsWith(ATTACHMENT_PENDING_PREFIX)).toBe(true);
    expect(await loadPendingAttachment(attachmentId)).toBeDefined();
  });

  it('removes the durable blob only after a successful upload', async () => {
    const store: AttachmentBlobStore = {
      upload: vi.fn(async () => ({
        objectKey: 'owners/u/notes/9/att-1',
        sizeBytes: 5,
        mimeType: 'image/png',
      })),
      download: vi.fn(),
      delete: vi.fn(),
    };
    setAttachmentBlobStoreForTests(store);

    const attachmentId = 'att-2';
    await storePendingAttachment(
      attachmentId,
      new Blob(['ok'], { type: 'image/png' }),
      'image/png',
      '9',
    );
    const note = {
      ...createEmptyNote({ id: '9', localId: 9 }),
      attachments: [
        {
          id: attachmentId,
          noteId: 9,
          storagePath: pendingStoragePath(attachmentId),
          type: 'image' as const,
          mimeType: 'image/png',
          sizeBytes: 2,
        },
      ],
    };

    const result = await syncNoteAttachments(note);
    expect(result.pendingCount).toBe(0);
    expect(result.note.attachments[0]?.storagePath.startsWith('r2:')).toBe(true);
    expect(await loadPendingAttachment(attachmentId)).toBeUndefined();
  });
});
