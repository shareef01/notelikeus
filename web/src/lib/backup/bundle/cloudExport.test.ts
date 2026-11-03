import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  applyBundle,
  buildBundleFromNotes,
  CLOUD_EXPORT_CONCURRENCY,
} from '@/lib/backup/bundle/bundleTransfer';
import { parseBackupBundle } from '@/lib/backup/bundle/backupBundle';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import {
  clearPendingAttachmentsForTests,
  getPendingAttachmentBlob,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { createEmptyNote, type Note } from '@/types/note';
import type { Attachment } from '@/types/attachment';

/**
 * AUD-001. Once an image has synced, its bytes live only in R2, so the old export (local bytes only)
 * left every synced image out of the backup the delete-account dialog offered. A complete export
 * downloads them through the authenticated Worker; these tests hold it to never calling a partial
 * archive complete, and to restoring every image it does claim.
 */

const OWNER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const RESTORE_OWNER = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

function image(seed: number, length = 4096): Uint8Array {
  const bytes = new Uint8Array(length);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  for (let index = 8; index < length; index++) bytes[index] = (index * 31 + seed) & 0xff;
  return bytes;
}

function cloudAttachment(noteId: number, id: string, bytes: Uint8Array): Attachment {
  return {
    id,
    noteId,
    storagePath: `r2:owners/${OWNER}/notes/${noteId}/${id}`,
    type: 'image',
    mimeType: 'image/png',
    sizeBytes: bytes.byteLength,
  };
}

function note(id: number, attachments: Attachment[]): Note {
  return createEmptyNote({ id: String(id), localId: id, title: `Note ${id}`, attachments });
}

/** The cloud as the Worker would serve it: one blob per (note, attachment). */
function fakeCloud(objects: Map<string, Uint8Array>) {
  const calls: string[] = [];
  let inFlight = 0;
  let maxInFlight = 0;
  return {
    calls,
    maxInFlight: () => maxInFlight,
    download: async (noteId: string, attachmentId: string): Promise<Blob> => {
      calls.push(`${noteId}/${attachmentId}`);
      inFlight++;
      maxInFlight = Math.max(maxInFlight, inFlight);
      try {
        await new Promise((resolve) => setTimeout(resolve, 2));
        const bytes = objects.get(`${noteId}/${attachmentId}`);
        if (!bytes) throw new Error('attachment download failed (404)');
        return new Blob([bytes as BlobPart], { type: 'image/png' });
      } finally {
        inFlight--;
      }
    },
  };
}

beforeEach(async () => {
  clearPendingAttachmentsForTests();
  await resetNotesDatabaseForTests();
});

describe('complete export of synced images', () => {
  it('the offline device export leaves synced images out and says it is incomplete', async () => {
    const bytes = image(1);
    const notes = [note(1, [cloudAttachment(1, 'cloud-1', bytes)])];

    const result = await buildBundleFromNotes(notes, OWNER);

    expect(result.mode).toBe('device');
    expect(result.complete).toBe(false);
    expect(result.attachmentsTotal).toBe(1);
    expect(result.attachmentsIncluded).toBe(0);
    expect(result.attachmentsSkipped).toBe(1);
    expect((await parseBackupBundle(result.bytes)).media.size).toBe(0);
  });

  it('downloads synced images, and they survive an export and a restore into a clean account', async () => {
    const synced = [image(1), image(2), image(3, 9000)];
    const stagedBytes = image(4);
    await storePendingAttachment('local-1', new Blob([stagedBytes as BlobPart], { type: 'image/png' }), 'image/png', '2', OWNER);
    const cloud = fakeCloud(
      new Map([
        ['1/cloud-1', synced[0]!],
        ['1/cloud-2', synced[1]!],
        ['2/cloud-3', synced[2]!],
      ]),
    );
    const notes = [
      note(1, [cloudAttachment(1, 'cloud-1', synced[0]!), cloudAttachment(1, 'cloud-2', synced[1]!)]),
      note(2, [
        cloudAttachment(2, 'cloud-3', synced[2]!),
        { id: 'local-1', noteId: 2, storagePath: pendingStoragePath('local-1'), type: 'image', mimeType: 'image/png' },
      ]),
    ];

    const result = await buildBundleFromNotes(notes, OWNER, {
      mode: 'complete',
      downloadCloudAttachment: cloud.download,
    });

    expect(result).toMatchObject({
      mode: 'complete',
      complete: true,
      attachmentsTotal: 4,
      attachmentsIncluded: 4,
      attachmentsSkipped: 0,
      attachmentsFailed: 0,
    });
    expect(cloud.calls.sort()).toEqual(['1/cloud-1', '1/cloud-2', '2/cloud-3']);

    // A clean environment: an empty database and a different account's staging namespace.
    clearPendingAttachmentsForTests();
    await resetNotesDatabaseForTests();
    const restored = await applyBundle(await parseBackupBundle(result.bytes), [], {
      ownerId: RESTORE_OWNER,
    });

    expect(restored.notesImported).toBe(2);
    expect(restored.attachmentsImported).toBe(4);
    expect(restored.attachmentsSkipped).toBe(0);
    const restoredBytes: Uint8Array[] = [];
    for (const restoredNote of restored.merged) {
      for (const attachment of restoredNote.attachments) {
        const pendingId = attachment.storagePath.slice('pending:'.length);
        // eslint-disable-next-line no-await-in-loop -- reads one staged image at a time
        const stagedBlob = await getPendingAttachmentBlob(pendingId, restoredNote.id, RESTORE_OWNER);
        expect(stagedBlob).toBeTruthy();
        // eslint-disable-next-line no-await-in-loop -- reads one staged image at a time
        restoredBytes.push(new Uint8Array(await stagedBlob!.blob.arrayBuffer()));
      }
    }
    const asHex = (bytes: Uint8Array) => Array.from(bytes, (byte) => byte.toString(16)).join('');
    expect(restoredBytes.map(asHex).sort()).toEqual([...synced, stagedBytes].map(asHex).sort());
  });

  it('never calls an export complete when a download fails, and says which images are missing', async () => {
    const bytes = image(1);
    const cloud = fakeCloud(new Map([['1/cloud-1', bytes]]));
    const notes = [note(1, [cloudAttachment(1, 'cloud-1', bytes), cloudAttachment(1, 'gone', image(2))])];

    const result = await buildBundleFromNotes(notes, OWNER, {
      mode: 'complete',
      downloadCloudAttachment: cloud.download,
    });

    expect(result.complete).toBe(false);
    expect(result.attachmentsIncluded).toBe(1);
    expect(result.attachmentsFailed).toBe(1);
    expect(result.warnings.join(' ')).toContain('could not be downloaded');
    // The failed download was retried once before it was given up.
    expect(cloud.calls.filter((call) => call === '1/gone')).toHaveLength(2);
    expect((await parseBackupBundle(result.bytes)).media.size).toBe(1);
  });

  it('rejects a truncated download rather than archiving it', async () => {
    const full = image(1, 8000);
    const cloud = fakeCloud(new Map([['1/cloud-1', full.slice(0, 5000)]]));
    const notes = [note(1, [cloudAttachment(1, 'cloud-1', full)])];

    const result = await buildBundleFromNotes(notes, OWNER, {
      mode: 'complete',
      downloadCloudAttachment: cloud.download,
    });

    expect(result.complete).toBe(false);
    expect(result.attachmentsFailed).toBe(1);
    expect(result.warnings.join(' ')).toContain('expected 8000');
  });

  it('counts an image the archive cannot store as not included', async () => {
    const huge = new Uint8Array(10 * 1024 * 1024 + 1);
    const cloud = fakeCloud(new Map([['1/cloud-1', huge]]));
    const notes = [note(1, [{ ...cloudAttachment(1, 'cloud-1', huge), sizeBytes: undefined }])];

    const result = await buildBundleFromNotes(notes, OWNER, {
      mode: 'complete',
      downloadCloudAttachment: cloud.download,
    });

    expect(result.complete).toBe(false);
    expect(result.attachmentsIncluded).toBe(0);
  });

  it('keeps at most the configured number of downloads in flight, and archives in note order', async () => {
    const objects = new Map<string, Uint8Array>();
    const attachments: Attachment[] = [];
    for (let index = 0; index < 12; index++) {
      const bytes = image(index);
      objects.set(`1/cloud-${index}`, bytes);
      attachments.push(cloudAttachment(1, `cloud-${index}`, bytes));
    }
    const cloud = fakeCloud(objects);

    const result = await buildBundleFromNotes([note(1, attachments)], OWNER, {
      mode: 'complete',
      downloadCloudAttachment: cloud.download,
    });

    expect(result.complete).toBe(true);
    expect(cloud.maxInFlight()).toBeLessThanOrEqual(CLOUD_EXPORT_CONCURRENCY);
    const manifest = (await parseBackupBundle(result.bytes)).manifest;
    expect(manifest.attachments.map((entry) => entry.attachmentId)).toEqual(
      attachments.map((attachment) => attachment.id),
    );
  });

  it('refuses a complete export with no way to reach the cloud', async () => {
    await expect(buildBundleFromNotes([], OWNER, { mode: 'complete' })).rejects.toThrow(
      'complete backup needs a signed-in connection',
    );
  });

  it('a guest library, with every image staged locally, is complete offline', async () => {
    const bytes = image(9);
    await storePendingAttachment('guest-1', new Blob([bytes as BlobPart], { type: 'image/png' }), 'image/png', '1', GUEST_OWNER_ID);
    const notes = [
      note(1, [{ id: 'guest-1', noteId: 1, storagePath: pendingStoragePath('guest-1'), type: 'image', mimeType: 'image/png' }]),
    ];

    const result = await buildBundleFromNotes(notes, GUEST_OWNER_ID);

    expect(result.complete).toBe(true);
    expect(result.attachmentsIncluded).toBe(1);
  });
});
