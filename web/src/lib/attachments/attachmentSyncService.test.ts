import { describe, expect, it } from 'vitest';
import {
  attachmentsKey,
  attachmentFromMetadata,
  createAttachmentId,
  pendingStoragePath,
} from '@/lib/attachments/attachmentPaths';
import { mergeAttachmentsIntoNotes } from '@/lib/attachments/attachmentSyncService';
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
