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
  AttachmentSyncIncompleteError,
  releaseUploadedPendingAttachments,
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
import { useAuthStore } from '@/store/authStore';
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
    // Staging is namespaced per owner, so a session has to exist for a blob to be stageable —
    // the same condition the editor runs under.
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: 'user-1', email: null, displayName: null });
    vi.unstubAllEnvs();
    vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'http://127.0.0.1:8787');
  });

  it('keeps pending blob retryable when upload fails, then releases after successful retry', async () => {
    const blob = new Blob(['attachment data'], { type: 'image/png' });
    // Staging is durable and awaited: the blob is only referenceable once IndexedDB has it.
    expect(await storePendingAttachment('att-pending', blob, 'image/png', 'note-1')).toBe(true);

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

    // Uploading releases nothing (AUD-011): the staged copy stays until the caller has persisted
    // a note that points at the uploaded one, and then releases it.
    expect(peekPendingAttachment('att-pending')).toBeDefined();
    await releaseUploadedPendingAttachments(note, syncedNote);
    expect(peekPendingAttachment('att-pending')).toBeUndefined();
  });

  // AUD-011: the second upload failing used to leave the first image's bytes released while the
  // persisted note still pointed at them, and every later save stopped at "missing local blob".
  it('keeps every staged copy when a later upload in the same note fails, and reports what landed', async () => {
    const first = new Blob(['first image'], { type: 'image/png' });
    const second = new Blob(['second image'], { type: 'image/png' });
    expect(await storePendingAttachment('att-a', first, 'image/png', 'note-3')).toBe(true);
    expect(await storePendingAttachment('att-b', second, 'image/png', 'note-3')).toBe(true);
    const note = {
      ...createEmptyNote({ id: 'note-3', localId: 3 }),
      attachments: ['att-a', 'att-b'].map((id) => ({
        id,
        noteId: 3,
        storagePath: pendingStoragePath(id),
        type: 'image' as const,
        mimeType: 'image/png',
      })),
    };
    const uploadMock = vi.fn(async (noteId: string, attachmentId: string, blob: Blob) => {
      if (attachmentId === 'att-b') throw new Error('R2 upload 503');
      return { objectKey: `owners/user-1/notes/${noteId}/${attachmentId}`, mimeType: 'image/png', sizeBytes: blob.size };
    });
    setAttachmentBlobStoreForTests({ upload: uploadMock, download: vi.fn(), delete: vi.fn() });

    const failure = await syncNoteAttachments(note).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(AttachmentSyncIncompleteError);
    const partial = (failure as AttachmentSyncIncompleteError).note;
    expect(partial.attachments.map((attachment) => attachment.storagePath)).toEqual([
      'r2:owners/user-1/notes/note-3/att-a',
      pendingStoragePath('att-b'),
    ]);
    // Nothing is released by the upload itself, including the one that landed.
    expect(peekPendingAttachment('att-a')?.blob).toBe(first);
    expect(peekPendingAttachment('att-b')?.blob).toBe(second);

    // Once the caller has persisted the partial note: exactly the uploaded copy goes.
    await releaseUploadedPendingAttachments(note, partial);
    expect(peekPendingAttachment('att-a')).toBeUndefined();
    expect(peekPendingAttachment('att-b')?.blob).toBe(second);

    // The next save retries only the image that failed.
    uploadMock.mockClear();
    uploadMock.mockImplementation(async (noteId: string, attachmentId: string, blob: Blob) => ({
      objectKey: `owners/user-1/notes/${noteId}/${attachmentId}`,
      mimeType: 'image/png',
      sizeBytes: blob.size,
    }));
    const retried = await syncNoteAttachments(partial);
    expect(uploadMock).toHaveBeenCalledTimes(1);
    expect(uploadMock.mock.calls[0]?.[1]).toBe('att-b');
    expect(retried.attachments.every((attachment) => attachment.storagePath.startsWith('r2:'))).toBe(true);
  });

  it('recovers a staged reference whose bytes were released after they had already been uploaded', async () => {
    const other = new Blob(['still staged'], { type: 'image/png' });
    expect(await storePendingAttachment('att-ok', other, 'image/png', 'note-4')).toBe(true);
    const note = {
      ...createEmptyNote({ id: 'note-4', localId: 4 }),
      attachments: ['att-gone', 'att-ok'].map((id) => ({
        id,
        noteId: 4,
        storagePath: pendingStoragePath(id),
        type: 'image' as const,
        mimeType: 'image/png',
      })),
    };
    const uploaded = new Blob(['the uploaded bytes'], { type: 'image/png' });
    const download = vi.fn(async () => uploaded);
    const upload = vi.fn(async (noteId: string, attachmentId: string, blob: Blob) => ({
      objectKey: `owners/user-1/notes/${noteId}/${attachmentId}`,
      mimeType: 'image/png',
      sizeBytes: blob.size,
    }));
    setAttachmentBlobStoreForTests({ upload, download, delete: vi.fn() });

    const synced = await syncNoteAttachments(note);

    expect(download).toHaveBeenCalledWith('note-4', 'att-gone');
    expect(synced.attachments[0]).toMatchObject({
      id: 'att-gone',
      storagePath: 'r2:owners/user-1/notes/note-4/att-gone',
      sizeBytes: uploaded.size,
    });
    // The lost one no longer stops the rest: the other image still uploads.
    expect(upload).toHaveBeenCalledTimes(1);
    expect(synced.attachments[1]?.storagePath).toBe('r2:owners/user-1/notes/note-4/att-ok');
  });

  it('releases only what was staged before and is in R2 after', async () => {
    const kept = new Blob(['kept'], { type: 'image/png' });
    expect(await storePendingAttachment('att-kept', kept, 'image/png', 'note-5')).toBe(true);
    const note = {
      ...createEmptyNote({ id: 'note-5', localId: 5 }),
      attachments: [
        { id: 'att-kept', noteId: 5, storagePath: pendingStoragePath('att-kept'), type: 'image' as const },
      ],
    };

    // The upload kept it staged (it failed), so it must not be released.
    await releaseUploadedPendingAttachments(note, note);

    expect(peekPendingAttachment('att-kept')?.blob).toBe(kept);
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
