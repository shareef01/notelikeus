import { afterEach, describe, expect, it } from 'vitest';
import {
  attachmentsFromBackupDtos,
  attachmentsToBackupDtos,
  bytesToBase64,
  base64ToBytes,
} from '@/lib/backup/backupAttachments';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import {
  clearPendingAttachmentsForTests,
  peekPendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { createEmptyNote } from '@/types/note';

afterEach(() => {
  clearPendingAttachmentsForTests();
});

describe('backupAttachments', () => {
  it('round-trips raw bytes through base64', () => {
    const bytes = new Uint8Array([1, 2, 255, 0]);
    expect(Array.from(base64ToBytes(bytesToBase64(bytes)) ?? [])).toEqual(Array.from(bytes));
    expect(base64ToBytes('%%%')).toBeNull();
  });

  it('exports pending images and restores them as pending', async () => {
    const png = new Uint8Array([9, 8, 7]);
    storePendingAttachment('att-1', new Blob([png], { type: 'image/png' }), 'image/png');
    const note = createEmptyNote({
      id: '4',
      localId: 4,
      attachments: [
        {
          id: 'att-1',
          noteId: 4,
          storagePath: pendingStoragePath('att-1'),
          type: 'image',
          mimeType: 'image/png',
        },
      ],
    });
    const dtos = await attachmentsToBackupDtos(note);
    expect(dtos).toHaveLength(1);
    expect(dtos[0].dataBase64).toBe(bytesToBase64(png));

    clearPendingAttachmentsForTests();
    const restored = attachmentsFromBackupDtos(dtos, 12, 3);
    expect(restored[0].storagePath).toBe(pendingStoragePath('att-1'));
    expect(peekPendingAttachment('att-1')?.mimeType).toBe('image/png');
  });

  it('skips version 2 payloads', () => {
    expect(
      attachmentsFromBackupDtos(
        [{ id: 'x', type: 'image', mimeType: 'image/png', dataBase64: 'AQID' }],
        1,
        2,
      ),
    ).toEqual([]);
  });
});
