import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BACKUP_VERSION, MAX_BACKUP_NOTES } from '@/lib/backup/constants';
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

  it('rejects exporting a library that exceeds MAX_BACKUP_NOTES', () => {
    const tooManyNotes: Note[] = Array.from({ length: MAX_BACKUP_NOTES + 1 }, (_, i) =>
      note(String(i + 1), { title: `Note ${i}` }),
    );
    expect(() => exportNotesBackup(tooManyNotes)).toThrow(/Cannot export backup: library has 25001 notes/);
  });

  it('round-trips a large dataset exceeding legacy 5,000 note limit with full fidelity', () => {
    const largeSet: Note[] = Array.from({ length: 5_500 }, (_, i) =>
      note(String(i + 1), {
        title: `Note ${i + 1}`,
        content: `Content for note ${i + 1}`,
        isPinned: i % 10 === 0,
        labels: [labelFromName(i % 2 === 0 ? 'Work' : 'Personal')],
      }),
    );
    const payload = exportPayload(largeSet);
    const { merged, result } = importNotesFromBackup(payload, []);
    expect(result.notesImported).toBe(5_500);
    expect(result.labelsCreated).toBe(2);
    expect(merged).toHaveLength(5_500);
    expect(merged[0].title).toBe('Note 1');
    expect(merged[5499].title).toBe('Note 5500');
  });

  it('preserves all note fields with full round-trip fidelity', () => {
    const futureReminder = Date.now() + 100_000;
    const testNote = note('42', {
      title: 'Full Fidelity Note',
      content: 'Multi-line\nDetailed\nContent',
      timestamp: 1_700_000_000,
      color: 0xff00ff,
      isPinned: true,
      isArchived: true,
      isTrashed: false,
      position: 5,
      reminderTimestamp: futureReminder,
      serverUpdatedAt: 1_700_000_500,
      labels: [labelFromName('TagA'), labelFromName('TagB')],
      checklist: [
        { id: 'c1', text: 'Task 1', isChecked: true, position: 0 },
        { id: 'c2', text: 'Task 2', isChecked: false, position: 1 },
      ],
    });
    const payload = exportPayload([testNote]);
    const { merged, result } = importNotesFromBackup(payload, []);
    expect(result.notesImported).toBe(1);
    const restored = merged[0];
    expect(restored.title).toBe('Full Fidelity Note');
    expect(restored.content).toBe('Multi-line\nDetailed\nContent');
    expect(restored.timestamp).toBe(1_700_000_000);
    expect(restored.color).toBe(0xff00ff);
    expect(restored.isPinned).toBe(true);
    expect(restored.isArchived).toBe(true);
    expect(restored.isTrashed).toBe(false);
    expect(restored.reminderTimestamp).toBe(futureReminder);
    expect(restored.labels.map((l) => l.name)).toEqual(['TagA', 'TagB']);
    expect(
      restored.checklist.map((c) => ({ text: c.text, isChecked: c.isChecked, position: c.position })),
    ).toEqual([
      { text: 'Task 1', isChecked: true, position: 0 },
      { text: 'Task 2', isChecked: false, position: 1 },
    ]);
  });
});
