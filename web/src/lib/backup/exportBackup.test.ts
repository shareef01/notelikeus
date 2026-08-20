import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BACKUP_VERSION } from '@/lib/backup/constants';
import { exportNotesBackup } from '@/lib/backup/exportBackup';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import { labelFromName } from '@/types/label';
import { createEmptyNote, type Note } from '@/types/note';

let exported: string;
let revoked: string[];
let clicks: number;

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id), ...overrides });
}

beforeEach(() => {
  exported = '';
  revoked = [];
  clicks = 0;
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url) => revoked.push(url));
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
    clicks += 1;
  });
  // Blob contents are only readable asynchronously, so capture the serialized payload as the
  // Blob is constructed instead — the export is synchronous and revokes its url immediately.
  const NativeBlob = globalThis.Blob;
  vi.stubGlobal(
    'Blob',
    class extends NativeBlob {
      constructor(parts: BlobPart[], options?: BlobPropertyBag) {
        super(parts, options);
        exported = parts.map((part) => String(part)).join('');
      }
    },
  );
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function exportPayload(notes: Note[]) {
  exportNotesBackup(notes);
  return JSON.parse(exported);
}

describe('exportNotesBackup', () => {
  it('downloads a dated json file and releases the blob url', () => {
    exportNotesBackup([note('1')]);
    expect(clicks).toBe(1);
    expect(revoked).toEqual(['blob:mock']);
  });

  it('writes the current backup version and app metadata', () => {
    const payload = exportPayload([note('1')]);
    expect(payload).toMatchObject({ version: BACKUP_VERSION, app: 'Notelikeus' });
    expect(typeof payload.exportedAt).toBe('number');
  });

  it('serializes notes with the Android field names', () => {
    const payload = exportPayload([
      note('7', {
        title: 'groceries',
        content: 'milk',
        position: 2,
        isPinned: true,
        labels: [labelFromName('Home')],
        checklist: [{ id: 'chk-1', text: 'bread', isChecked: true, position: 0 }],
      }),
    ]);
    expect(payload.notes[0]).toMatchObject({
      id: 7,
      title: 'groceries',
      content: 'milk',
      position: 2,
      isPinned: true,
      labels: ['Home'],
      checklist: [{ text: 'bread', isChecked: true, position: 0 }],
    });
    // Local-only checklist ids are not part of the interchange format.
    expect(payload.notes[0].checklist[0]).not.toHaveProperty('id');
  });

  it('omits null reminder and server timestamps but keeps real ones', () => {
    const plain = exportPayload([note('1')]).notes[0];
    expect(plain).not.toHaveProperty('reminderTimestamp');
    expect(plain).not.toHaveProperty('serverUpdatedAt');

    const stamped = exportPayload([note('1', { reminderTimestamp: 111, serverUpdatedAt: 222 })])
      .notes[0];
    expect(stamped).toMatchObject({ reminderTimestamp: 111, serverUpdatedAt: 222 });
  });

  it('collects labels once, case-insensitively, sorted by name', () => {
    const payload = exportPayload([
      note('1', { labels: [labelFromName('Work')] }),
      note('2', { labels: [labelFromName('work'), labelFromName('Home')] }),
    ]);
    expect(payload.labels.map((l: { name: string }) => l.name)).toEqual(['Home', 'work']);
  });

  it('round-trips through the importer', () => {
    const payload = exportPayload([
      note('7', {
        title: 'groceries',
        content: 'milk',
        labels: [labelFromName('Home')],
        checklist: [{ id: 'chk-1', text: 'bread', isChecked: true, position: 0 }],
      }),
    ]);

    const { merged, result } = importNotesFromBackup(payload, []);
    expect(result).toEqual({ notesImported: 1, labelsCreated: 1 });
    expect(merged[0]).toMatchObject({
      title: 'groceries',
      content: 'milk',
      labels: [{ name: 'Home' }],
      checklist: [{ text: 'bread', isChecked: true, position: 0 }],
    });
  });
});
