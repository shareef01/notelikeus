import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/attachments/supabaseAttachmentMetadata', () => ({
  listUserAttachments: vi.fn().mockResolvedValue([]),
  listPendingDeletedAttachments: vi.fn().mockResolvedValue([]),
  purgeDeletedNoteAttachment: vi.fn().mockResolvedValue(undefined),
}));
import {
  attachmentsKey,
  attachmentFromMetadata,
  createAttachmentId,
  pendingStoragePath,
} from '@/lib/attachments/attachmentPaths';
import {
  gcAttachmentsAfterNoteDelete,
  mergeAttachmentsIntoNotes,
  retryPendingAttachmentGc,
  syncNoteAttachments,
} from '@/lib/attachments/attachmentSyncService';
import {
  clearPendingAttachmentsForTests,
  peekPendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import {
  resetAttachmentBlobStoreForTests,
  setAttachmentBlobStoreForTests,
} from '@/lib/attachments/attachmentBlobStoreRegistry';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

describe('attachmentPaths', () => {
  it('creates stable pending storage paths', () => {
    const id = createAttachmentId();
    expect(pendingStoragePath(id)).toBe(`pending:${id}`);
    expect(attachmentsKey([])).toBe('');
  });
});

describe('mergeAttachmentsIntoNotes', () => {
  it('merges remote metadata and keeps local pending uploads', () => {
    const note = createEmptyNote({ id: 'note-9', localId: 9 });
    const pendingId = 'pending-local';
    const withPending = {
      ...note,
      attachments: [
        {
          id: pendingId,
          noteId: 9,
          storagePath: pendingStoragePath(pendingId),
          type: 'image',
          mimeType: 'image/png',
          sizeBytes: 12,
        },
      ],
    };
    const merged = mergeAttachmentsIntoNotes([withPending], [
      {
        attachmentId: 'remote-1',
        noteId: 'note-9',
        objectKey: 'owners/u/notes/note-9/remote-1',
        mimeType: 'image/jpeg',
        sizeBytes: 20,
        attachmentType: 'image',
        createdAt: 1,
      },
    ]);
    expect(merged[0]?.attachments).toHaveLength(2);
    expect(merged[0]?.attachments[0]?.id).toBe('remote-1');
    expect(merged[0]?.attachments[1]?.id).toBe(pendingId);
    expect(attachmentFromMetadata(note, {
      attachmentId: 'remote-1',
      noteId: 'note-9',
      objectKey: 'owners/u/notes/note-9/remote-1',
      mimeType: 'image/jpeg',
      sizeBytes: 20,
      attachmentType: 'image',
      createdAt: 1,
    }).storagePath).toContain('r2:');
  });
});

describe('gcAttachmentsAfterNoteDelete', () => {
  beforeEach(() => {
    resetAttachmentBlobStoreForTests();
    useTombstoneStore.getState().reset();
    vi.unstubAllEnvs();
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'http://127.0.0.1:8787');
  });

  it('throws when a remote delete fails so GC stays retryable', async () => {
    const del = vi.fn().mockRejectedValue(new Error('r2 503'));
    setAttachmentBlobStoreForTests({
      upload: vi.fn(),
      download: vi.fn(),
      delete: del,
    });
    await expect(
      gcAttachmentsAfterNoteDelete('note-1', [{
        id: 'att-1',
        noteId: 1,
        storagePath: 'r2:owners/u/notes/note-1/att-1',
        type: 'image',
      }]),
    ).rejects.toThrow(/r2 503/);
    expect(del).toHaveBeenCalledWith('note-1', 'att-1');
  });

  it('retries pending GC and skips a note that has been restored', async () => {
    const del = vi.fn().mockResolvedValue(undefined);
    setAttachmentBlobStoreForTests({
      upload: vi.fn(),
      download: vi.fn(),
      delete: del,
    });
    useTombstoneStore.getState().markPendingAttachmentGc('keep', ['att-keep']);
    useTombstoneStore.getState().markPendingAttachmentGc('restored', ['att-restored']);
    useTombstoneStore.getState().markRestored('restored');

    await retryPendingAttachmentGc();

    expect(del).toHaveBeenCalledWith('keep', 'att-keep');
    expect(del).not.toHaveBeenCalledWith('restored', 'att-restored');
    expect(useTombstoneStore.getState().pendingAttachmentGcByNoteId.keep).toBeUndefined();
    expect(useTombstoneStore.getState().pendingAttachmentGcByNoteId.restored).toBeUndefined();
  });
});

describe('syncNoteAttachments', () => {
  beforeEach(() => {
    resetAttachmentBlobStoreForTests();
    clearPendingAttachmentsForTests();
    vi.unstubAllEnvs();
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'http://127.0.0.1:8787');
  });

  it('keeps pending blob retryable when upload fails, then releases after successful retry', async () => {
    const blob = new Blob(['attachment data'], { type: 'image/png' });
    storePendingAttachment('att-pending', blob, 'image/png', 'note-1');

    const note = {
      ...createEmptyNote({ id: 'note-1', localId: 1 }),
      attachments: [
        {
          id: 'att-pending',
          noteId: 1,
          storagePath: pendingStoragePath('att-pending'),
          type: 'image' as const,
          mimeType: 'image/png',
          sizeBytes: blob.size,
        },
      ],
    };

    const uploadMock = vi
      .fn()
      .mockRejectedValueOnce(new Error('R2 upload 500'))
      .mockResolvedValueOnce({
        objectKey: 'owners/user-1/notes/note-1/att-pending',
        mimeType: 'image/png',
        sizeBytes: blob.size,
      });

    setAttachmentBlobStoreForTests({
      upload: uploadMock,
      download: vi.fn(),
      delete: vi.fn(),
    });

    // Attempt 1: Upload fails
    await expect(syncNoteAttachments(note)).rejects.toThrow(/R2 upload 500/);

    // Pending Blob MUST still exist for retry
    const surviving = peekPendingAttachment('att-pending');
    expect(surviving).toBeDefined();
    expect(surviving?.blob).toBe(blob);

    // Attempt 2: Retry succeeds
    const syncedNote = await syncNoteAttachments(note);
    expect(syncedNote.attachments?.[0]?.storagePath).toBe(
      'r2:owners/user-1/notes/note-1/att-pending',
    );

    // Pending Blob must now be released
    expect(peekPendingAttachment('att-pending')).toBeUndefined();
  });

  it('throws an explicit error when pending metadata reference exists but blob is missing', async () => {
    const note = {
      ...createEmptyNote({ id: 'note-missing', localId: 2 }),
      attachments: [
        {
          id: 'att-lost',
          noteId: 2,
          storagePath: pendingStoragePath('att-lost'),
          type: 'image' as const,
          mimeType: 'image/png',
        },
      ],
    };

    setAttachmentBlobStoreForTests({
      upload: vi.fn(),
      download: vi.fn(),
      delete: vi.fn(),
    });

    await expect(syncNoteAttachments(note)).rejects.toThrow(
      /Missing local blob for pending attachment att-lost/,
    );
  });
});
