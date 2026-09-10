import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  BUNDLE_FORMAT_VERSION,
  BundleFormatError,
  buildBackupBundle,
  mediaEntryName,
  parseBackupBundle,
  sha256Hex,
} from '@/lib/backup/bundle/backupBundle';
import {
  applyBundle,
  buildBundleFromNotes,
  bundleFileName,
} from '@/lib/backup/bundle/bundleTransfer';
import { readZip, writeZip } from '@/lib/backup/bundle/zip';
import { exportBackupPayload } from '@/lib/backup/exportBackup';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { clearPendingAttachmentsForTests, storePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { labelFromName } from '@/types/label';
import { createEmptyNote, type Note } from '@/types/note';

const encoder = new TextEncoder();
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id), ...overrides });
}

async function stage(attachmentId: string, noteId: string, bytes: Uint8Array) {
  const ok = await storePendingAttachment(
    attachmentId,
    new Blob([bytes as BlobPart], { type: 'image/png' }),
    'image/png',
    noteId,
    GUEST_OWNER_ID,
  );
  expect(ok).toBe(true);
}

beforeEach(async () => {
  clearPendingAttachmentsForTests();
  await resetNotesDatabaseForTests();
});

describe('bundle format', () => {
  it('embeds the v3 backup document verbatim', async () => {
    const notes = [note('7', { title: 'Trip', labels: [labelFromName('Travel')] })];
    const document = exportBackupPayload(notes, 1767225600000);

    const archive = await buildBackupBundle(document, [], { exportedAt: 1767225600000 });
    const entries = await readZip(archive);
    const manifestEntry = entries.find((entry) => entry.name === 'manifest.json');
    const manifest = JSON.parse(new TextDecoder().decode(manifestEntry!.data));

    expect(manifest.formatVersion).toBe(BUNDLE_FORMAT_VERSION);
    expect(manifest.backup).toEqual(document);
    // The point of "verbatim": a v3-only reader can lift this out and import it unchanged.
    const { result } = importNotesFromBackup(manifest.backup, []);
    expect(result.notesImported).toBe(1);
  });

  it('round-trips attachment bytes and their checksum', async () => {
    const document = exportBackupPayload([note('7')], 1);
    const archive = await buildBackupBundle(document, [
      { noteId: 7, attachmentId: 'att-one', type: 'image', mimeType: 'image/png', bytes: PNG },
    ]);

    const parsed = await parseBackupBundle(archive);
    expect(parsed.warnings).toEqual([]);
    expect(parsed.manifest.attachments).toHaveLength(1);
    expect(parsed.manifest.attachments[0]).toMatchObject({
      noteId: 7,
      attachmentId: 'att-one',
      path: 'media/att-one',
      mimeType: 'image/png',
      sizeBytes: PNG.byteLength,
      sha256: await sha256Hex(PNG),
    });
    expect([...parsed.media.get('att-one')!.bytes]).toEqual([...PNG]);
  });

  it('names the file with the bundle extension', () => {
    expect(bundleFileName(new Date('2026-07-08T12:00:00Z'))).toBe(
      'notelikeus_backup_2026-07-08.nlkbak',
    );
  });
});

describe('bundle parsing refuses what it cannot trust', () => {
  it('rejects a plain JSON backup', async () => {
    const json = encoder.encode(JSON.stringify(exportBackupPayload([note('1')], 1)));
    await expect(parseBackupBundle(json)).rejects.toThrow(BundleFormatError);
  });

  it('rejects an archive with no manifest', async () => {
    const archive = writeZip([{ name: 'media/att-one', data: PNG }]);
    await expect(parseBackupBundle(archive)).rejects.toThrow(/no manifest/);
  });

  it('rejects a manifest that is not JSON', async () => {
    const archive = writeZip([{ name: 'manifest.json', data: encoder.encode('{not json') }]);
    await expect(parseBackupBundle(archive)).rejects.toThrow(/not valid JSON/);
  });

  it('rejects a manifest carrying no backup document', async () => {
    const archive = writeZip([
      { name: 'manifest.json', data: encoder.encode(JSON.stringify({ formatVersion: 4 })) },
    ]);
    await expect(parseBackupBundle(archive)).rejects.toThrow(/no backup document/);
  });

  it('rejects a bundle from a future format version', async () => {
    const archive = writeZip([
      {
        name: 'manifest.json',
        data: encoder.encode(
          JSON.stringify({ formatVersion: BUNDLE_FORMAT_VERSION + 1, backup: { version: 3, notes: [] } }),
        ),
      },
    ]);
    await expect(parseBackupBundle(archive)).rejects.toThrow(/Unsupported bundle version/);
  });

  it('rejects an oversized manifest before extracting media', async () => {
    const { MAX_BUNDLE_MANIFEST_BYTES } = await import('@/lib/backup/bundle/backupBundle');
    const huge = new Uint8Array(MAX_BUNDLE_MANIFEST_BYTES + 1);
    huge.fill(0x20); // spaces — valid-ish payload shape, refused by size alone
    const archive = writeZip([
      { name: 'manifest.json', data: huge },
      { name: 'media/att-one', data: PNG },
    ]);
    await expect(parseBackupBundle(archive)).rejects.toThrow(/manifest is too large/);
  });

  it('warns about unreferenced media without importing it', async () => {
    const document = exportBackupPayload([note('7', { title: 'Trip' })], 1);
    const archive = writeZip([
      {
        name: 'manifest.json',
        data: encoder.encode(
          JSON.stringify({
            formatVersion: 4,
            backup: document,
            attachments: [],
          }),
        ),
      },
      { name: 'media/stray-one', data: new Uint8Array(64 * 1024).fill(7) },
      { name: 'media/stray-two', data: new Uint8Array(64 * 1024).fill(8) },
    ]);

    const parsed = await parseBackupBundle(archive);
    expect(parsed.media.size).toBe(0);
    expect(parsed.warnings.join(' ')).toMatch(/not listed in its manifest/);
    const plan = await applyBundle(parsed, [], { ownerId: GUEST_OWNER_ID });
    expect(plan.notesImported).toBe(1);
  });

  it('extracts attachments only after the manifest validates', async () => {
    const archive = writeZip([
      {
        name: 'manifest.json',
        data: encoder.encode(
          JSON.stringify({
            formatVersion: BUNDLE_FORMAT_VERSION + 1,
            backup: { version: 3, notes: [] },
            attachments: [
              {
                noteId: 7,
                attachmentId: 'att-one',
                path: 'media/att-one',
                type: 'image',
                sizeBytes: PNG.byteLength,
              },
            ],
          }),
        ),
      },
      { name: 'media/att-one', data: PNG },
    ]);
    // Future format version fails on the manifest; media must not be required to reach that error.
    await expect(parseBackupBundle(archive)).rejects.toThrow(/Unsupported bundle version/);
  });
});

describe('a corrupt attachment never costs a note', () => {
  async function bundleWithTamperedAttachment() {
    const document = exportBackupPayload([note('7', { title: 'Trip' })], 1);
    const archive = await buildBackupBundle(document, [
      { noteId: 7, attachmentId: 'att-one', type: 'image', mimeType: 'image/png', bytes: PNG },
    ]);
    // Rebuild the archive with different bytes under the same name, so the manifest's sha256
    // and sizeBytes no longer describe the file. This is what bit rot or a hand-edited archive
    // looks like — the ZIP's own CRC still matches, so only the manifest catches it.
    const entries = await readZip(archive);
    return writeZip(
      entries.map((entry) =>
        entry.name === mediaEntryName('att-one')
          ? { name: entry.name, data: new Uint8Array([9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9]) }
          : entry,
      ),
    );
  }

  it('drops the attachment, keeps the note, and says so', async () => {
    const parsed = await parseBackupBundle(await bundleWithTamperedAttachment());
    expect(parsed.media.has('att-one')).toBe(false);
    expect(parsed.warnings.join(' ')).toMatch(/checksum/);

    const plan = await applyBundle(parsed, [], { ownerId: GUEST_OWNER_ID });
    expect(plan.notesImported).toBe(1);
    expect(plan.merged[0]!.title).toBe('Trip');
    expect(plan.merged[0]!.attachments).toEqual([]);
  });

  it('keeps the note when the media file is missing entirely', async () => {
    const document = exportBackupPayload([note('7', { title: 'Trip' })], 1);
    const archive = await buildBackupBundle(document, [
      { noteId: 7, attachmentId: 'att-one', type: 'image', mimeType: 'image/png', bytes: PNG },
    ]);
    const entries = await readZip(archive);
    const withoutMedia = writeZip(entries.filter((entry) => entry.name === 'manifest.json'));

    const parsed = await parseBackupBundle(withoutMedia);
    expect(parsed.warnings.join(' ')).toMatch(/its file is missing/);

    const plan = await applyBundle(parsed, [], { ownerId: GUEST_OWNER_ID });
    expect(plan.notesImported).toBe(1);
    expect(plan.attachmentsSkipped).toBe(1);
    expect(plan.merged[0]!.title).toBe('Trip');
  });

  it('ignores an attachment id that could be a path', async () => {
    const archive = writeZip([
      {
        name: 'manifest.json',
        data: encoder.encode(
          JSON.stringify({
            formatVersion: 4,
            backup: exportBackupPayload([note('7')], 1),
            attachments: [
              { noteId: 7, attachmentId: '../../etc/passwd', path: '../../etc/passwd' },
              { noteId: 7, attachmentId: 'media/../escape', path: 'media/../escape' },
            ],
          }),
        ),
      },
    ]);

    const parsed = await parseBackupBundle(archive);
    expect(parsed.media.size).toBe(0);
    expect(parsed.warnings.filter((w) => w.includes('unusable id'))).toHaveLength(2);
  });

  it('reads media by validated id, not by the manifest path', async () => {
    // The manifest points `path` somewhere else entirely. The reader must ignore it and look
    // for media/<attachmentId>, which is the only place it will ever read from.
    const archive = writeZip([
      {
        name: 'manifest.json',
        data: encoder.encode(
          JSON.stringify({
            formatVersion: 4,
            backup: exportBackupPayload([note('7')], 1),
            attachments: [
              {
                noteId: 7,
                attachmentId: 'att-one',
                path: 'manifest.json',
                type: 'image',
                sizeBytes: PNG.byteLength,
              },
            ],
          }),
        ),
      },
      { name: mediaEntryName('att-one'), data: PNG },
    ]);

    const parsed = await parseBackupBundle(archive);
    expect([...parsed.media.get('att-one')!.bytes]).toEqual([...PNG]);
    expect(parsed.manifest.attachments[0]!.path).toBe('media/att-one');
  });
});

describe('bundle import stays additive and deterministic', () => {
  it('re-attaches images to the notes they came from', async () => {
    await stage('att-one', '7', PNG);
    const source = note('7', {
      title: 'Trip',
      attachments: [
        {
          id: 'att-one',
          noteId: 7,
          storagePath: pendingStoragePath('att-one'),
          type: 'image',
          mimeType: 'image/png',
          sizeBytes: PNG.byteLength,
        },
      ],
    });

    const exported = await buildBundleFromNotes([source], GUEST_OWNER_ID);
    expect(exported.attachmentsIncluded).toBe(1);
    expect(exported.attachmentsSkipped).toBe(0);

    const plan = await applyBundle(await parseBackupBundle(exported.bytes), [], { ownerId: GUEST_OWNER_ID });
    expect(plan.notesImported).toBe(1);
    expect(plan.attachmentsImported).toBe(1);
    const [restored] = plan.merged;
    expect(restored!.title).toBe('Trip');
    expect(restored!.attachments).toHaveLength(1);
    expect(restored!.attachments[0]!.mimeType).toBe('image/png');
    // A fresh id, not the one from the file: two imports must not share a staged blob.
    expect(restored!.attachments[0]!.id).not.toBe('att-one');
    expect(restored!.attachments[0]!.storagePath).toBe(
      pendingStoragePath(restored!.attachments[0]!.id),
    );
    expect(restored!.attachments[0]!.noteId).toBe(restored!.localId);
  });

  it('importing the same bundle twice makes two independent copies', async () => {
    await stage('att-one', '7', PNG);
    const source = note('7', {
      title: 'Trip',
      attachments: [
        {
          id: 'att-one',
          noteId: 7,
          storagePath: pendingStoragePath('att-one'),
          type: 'image',
          mimeType: 'image/png',
        },
      ],
    });
    const exported = await buildBundleFromNotes([source], GUEST_OWNER_ID);

    const first = await applyBundle(await parseBackupBundle(exported.bytes), [], { ownerId: GUEST_OWNER_ID });
    const second = await applyBundle(await parseBackupBundle(exported.bytes), first.merged, { ownerId: GUEST_OWNER_ID });

    expect(second.merged).toHaveLength(2);
    expect(second.merged.map((n) => n.title)).toEqual(['Trip', 'Trip']);
    const ids = second.merged.map((n) => n.id);
    expect(new Set(ids).size).toBe(2);
    const attachmentIds = second.merged.flatMap((n) => n.attachments.map((a) => a.id));
    expect(attachmentIds).toHaveLength(2);
    expect(new Set(attachmentIds).size).toBe(2);
  });

  it('maps each attachment to the right note when several are imported', async () => {
    await stage('att-a', '1', PNG);
    await stage('att-b', '2', new Uint8Array([1, 1, 1, 1]));
    const notes = [
      note('1', {
        title: 'first',
        attachments: [
          { id: 'att-a', noteId: 1, storagePath: pendingStoragePath('att-a'), type: 'image' },
        ],
      }),
      note('2', {
        title: 'second',
        attachments: [
          { id: 'att-b', noteId: 2, storagePath: pendingStoragePath('att-b'), type: 'image' },
        ],
      }),
      note('3', { title: 'third, no image' }),
    ];

    const exported = await buildBundleFromNotes(notes, GUEST_OWNER_ID);
    const plan = await applyBundle(await parseBackupBundle(exported.bytes), [], { ownerId: GUEST_OWNER_ID });

    expect(plan.merged.map((n) => n.title)).toEqual(['first', 'second', 'third, no image']);
    expect(plan.merged.map((n) => n.attachments.length)).toEqual([1, 1, 0]);
    expect(plan.merged[0]!.attachments[0]!.sizeBytes).toBe(PNG.byteLength);
    expect(plan.merged[1]!.attachments[0]!.sizeBytes).toBe(4);
  });

  it('adds to an existing library rather than replacing it', async () => {
    const existing = [note('100', { title: 'already here' })];
    const exported = await buildBundleFromNotes([note('7', { title: 'from the bundle' })], GUEST_OWNER_ID);

    const plan = await applyBundle(await parseBackupBundle(exported.bytes), existing, { ownerId: GUEST_OWNER_ID });

    expect(plan.merged.map((n) => n.title)).toEqual(['already here', 'from the bundle']);
    expect(plan.merged[0]!.id).toBe('100');
  });

  it('reports cloud-only images as skipped rather than reaching the network', async () => {
    const cloudOnly = note('7', {
      title: 'Trip',
      attachments: [
        { id: 'att-r2', noteId: 7, storagePath: 'r2:owners/u/notes/7/att-r2', type: 'image' },
      ],
    });

    const exported = await buildBundleFromNotes([cloudOnly], GUEST_OWNER_ID);

    expect(exported.attachmentsIncluded).toBe(0);
    expect(exported.attachmentsSkipped).toBe(1);
    expect(exported.warnings.join(' ')).toMatch(/only in the cloud/);
    // The note itself is still fully in the bundle.
    const plan = await applyBundle(await parseBackupBundle(exported.bytes), [], { ownerId: GUEST_OWNER_ID });
    expect(plan.merged[0]!.title).toBe('Trip');
  });

  it('carries checklists, labels, colours and reminders through the bundle', async () => {
    const source = note('7', {
      title: 'Trip',
      content: '**pack** bags',
      color: -2955,
      isPinned: true,
      reminderTimestamp: 4102444800000,
      labels: [labelFromName('Travel'), labelFromName('Work')],
      checklist: [
        { id: 'chk-1', text: 'Passport', isChecked: true, position: 0 },
        { id: 'chk-2', text: 'Charger', isChecked: false, position: 1 },
      ],
    });

    const exported = await buildBundleFromNotes([source], GUEST_OWNER_ID);
    const plan = await applyBundle(await parseBackupBundle(exported.bytes), [], { ownerId: GUEST_OWNER_ID });
    const restored = plan.merged[0]!;

    expect(restored).toMatchObject({
      title: 'Trip',
      content: '**pack** bags',
      color: -2955,
      isPinned: true,
      reminderTimestamp: 4102444800000,
    });
    expect(restored.labels.map((l) => l.name).sort()).toEqual(['Travel', 'Work']);
    expect(restored.checklist.map((item) => [item.text, item.isChecked, item.position])).toEqual([
      ['Passport', true, 0],
      ['Charger', false, 1],
    ]);
    expect(plan.labelsCreated).toBe(2);
  });

  it('carries no tokens, keys, cursors or owner identifiers', async () => {
    await stage('att-one', '7', PNG);
    const source = note('7', {
      title: 'Trip',
      serverUpdatedAt: 1767225600000,
      attachments: [
        { id: 'att-one', noteId: 7, storagePath: pendingStoragePath('att-one'), type: 'image' },
      ],
    });

    const exported = await buildBundleFromNotes([source], GUEST_OWNER_ID);
    const manifestEntry = (await readZip(exported.bytes)).find(
      (entry) => entry.name === 'manifest.json',
    )!;
    const text = new TextDecoder().decode(manifestEntry.data);

    for (const forbidden of [
      'access_token',
      'refresh_token',
      'accessToken',
      'refreshToken',
      'supabase',
      'eyJhbGciOi',
      'knownCloudIds',
      'lastRemoteRevision',
      'noteRevisions',
      'ownerId',
      'owners/',
      'apikey',
    ]) {
      expect(text, `manifest must not contain ${forbidden}`).not.toContain(forbidden);
    }
  });
});
