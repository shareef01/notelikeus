import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Note } from '@/types/note';

const listUserAttachments = vi.fn();
vi.mock('@/lib/attachments/supabaseAttachmentMetadata', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/attachments/supabaseAttachmentMetadata')>()),
  listUserAttachments: () => listUserAttachments(),
}));
vi.mock('@/lib/attachments/attachmentConfig', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/attachments/attachmentConfig')>()),
  isR2AttachmentsEnabled: () => true,
}));
vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    auth: {
      getSession: async () => ({ data: { session: { access_token: 'token' } }, error: null }),
      getUser: async () => ({ data: { user: { id: 'owner-1' } }, error: null }),
    },
  }),
  isSupabaseBackendEnabled: () => true,
}));

import { keepingLocalOnlyAttachments, pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import {
  resetAttachmentBlobStoreForTests,
  setAttachmentBlobStoreForTests,
} from '@/lib/attachments/attachmentBlobStoreRegistry';
import { mergeAttachmentsIntoNotes } from '@/lib/attachments/attachmentSyncService';
import { reclaimUncommittedUploads } from '@/lib/attachments/conflictCopyAttachments';
import { clearPendingAttachmentsForTests } from '@/lib/attachments/pendingAttachmentStore';
import { createR2AttachmentBlobStore } from '@/lib/attachments/r2AttachmentBlobStore';
import { getPendingAttachment } from '@/lib/local/pendingAttachmentRepository';
import { noteToSupabaseRpcArgs } from '@/lib/supabase/supabaseNoteMapper';
import { mergeRemoteNotes } from '@/lib/notes/remoteMerge';
import { useAuthStore } from '@/store/authStore';
import type { Attachment } from '@/types/attachment';
import { createEmptyNote } from '@/types/note';

const OWNER = 'owner-1';

function uploaded(id: string, noteId = '7'): Attachment {
  return { id, noteId: Number(noteId), storagePath: `r2:owners/${OWNER}/notes/${noteId}/${id}`, type: 'image', mimeType: 'image/png' };
}

function staged(id: string): Attachment {
  return { id, noteId: 7, storagePath: pendingStoragePath(id), type: 'image', mimeType: 'image/png' };
}

function note(attachments: Attachment[]): Note {
  return { ...createEmptyNote({ id: '7', localId: 7, title: 'Trip' }), attachments };
}

function metadataRow(attachmentId: string, noteId = '7') {
  return {
    attachmentId,
    noteId,
    objectKey: `owners/${OWNER}/notes/${noteId}/${attachmentId}`,
    mimeType: 'image/png',
    sizeBytes: 5,
    attachmentType: 'image',
  };
}

/** AU-17: the web on the deferred attachment protocol, as the Kotlin clients are. */
describe('deferred attachment protocol (web)', () => {
  beforeEach(() => {
    listUserAttachments.mockReset();
    clearPendingAttachmentsForTests();
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: OWNER, email: null, displayName: null });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    resetAttachmentBlobStoreForTests();
  });

  it('uploads on the v2 route, which keeps the row provisional until a commit names it', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ objectKey: 'k', sizeBytes: 3, mimeType: 'image/png' })));
    vi.stubGlobal('fetch', fetchMock);

    await createR2AttachmentBlobStore('https://worker.test').upload('7', 'att-1', new Blob(['abc']), 'image/png');

    expect(fetchMock).toHaveBeenCalledWith('https://worker.test/v2/attachments/7/att-1', expect.objectContaining({ method: 'PUT' }));
  });

  it('names exactly the uploaded attachments in the commit, and none for restore_note', () => {
    const version = note([uploaded('att-up'), staged('att-local')]);

    expect(noteToSupabaseRpcArgs(version, 3)).toHaveProperty('p_attachment_ids', ['att-up']);
    expect(noteToSupabaseRpcArgs(version, 3, { attachmentIds: false })).not.toHaveProperty('p_attachment_ids');
  });

  it("keeps a queued note's own uploads through hydration, which lists committed rows only", () => {
    const local = note([uploaded('att-up')]);

    expect(mergeAttachmentsIntoNotes([local], [])[0].attachments).toEqual([]);
    expect(mergeAttachmentsIntoNotes([local], [], new Set(['7']))[0].attachments).toEqual([uploaded('att-up')]);
  });

  describe('a conflict on a push that was to name an upload', () => {
    const download = vi.fn();
    beforeEach(() => {
      download.mockReset();
      setAttachmentBlobStoreForTests({ upload: vi.fn(), delete: vi.fn(), download });
    });

    it('fetches the uncommitted upload back as a staged image, and leaves committed ones', async () => {
      listUserAttachments.mockResolvedValue([metadataRow('att-committed')]);
      download.mockResolvedValue(new Blob(['bytes'], { type: 'image/png' }));

      const reclaimed = await reclaimUncommittedUploads(note([uploaded('att-committed'), uploaded('att-new')]), OWNER, {
        noteGone: false,
      });

      expect(download).toHaveBeenCalledTimes(1);
      expect(download).toHaveBeenCalledWith('7', 'att-new');
      expect(reclaimed.attachments.map((attachment) => attachment.storagePath)).toEqual([
        uploaded('att-committed').storagePath,
        pendingStoragePath('att-new'),
      ]);
      expect((await getPendingAttachment(OWNER, '7', 'att-new'))?.sizeBytes).toBe(5);
    });

    it('fails rather than lose the image when it cannot be fetched, so the flush retries later', async () => {
      listUserAttachments.mockResolvedValue([]);
      download.mockRejectedValue(new Error('attachment download failed (503)'));

      await expect(reclaimUncommittedUploads(note([uploaded('att-new')]), OWNER, { noteGone: false })).rejects.toThrow(
        '503',
      );
    });

    it('skips an unreadable upload of a note the cloud deleted (its rows went with it)', async () => {
      listUserAttachments.mockResolvedValue([]);
      download.mockRejectedValue(new Error('attachment download failed (401)'));

      const reclaimed = await reclaimUncommittedUploads(note([uploaded('att-new')]), OWNER, { noteGone: true });

      expect(reclaimed.attachments).toEqual([]);
    });
  });

  describe('a cloud version written over the local row (AU-23)', () => {
    it("keeps the local row's images that never uploaded", () => {
      const cloud = note([]);
      expect(keepingLocalOnlyAttachments(cloud, note([uploaded('att-up'), staged('att-local')])).attachments).toEqual([
        staged('att-local'),
      ]);
    });

    it('keeps them when a reconcile lets the cloud version win', async () => {
      const local = { ...note([staged('att-local')]), timestamp: 1, serverUpdatedAt: 100 };
      const remote = { ...note([uploaded('att-up')]), timestamp: 2, serverUpdatedAt: 200 };

      const [merged] = await mergeRemoteNotes([local], [remote]);

      expect(merged.attachments.map((attachment) => attachment.id)).toEqual(['att-up', 'att-local']);
    });
  });
});
