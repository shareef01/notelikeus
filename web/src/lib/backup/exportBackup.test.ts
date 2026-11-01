import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BACKUP_VERSION,
  MAX_BACKUP_FILE_BYTES,
  MAX_NOTE_CHECKLIST_ITEMS,
  MAX_NOTE_CONTENT_CHARS,
  MAX_NOTE_LABELS,
  MAX_NOTE_TITLE_CHARS,
} from '@/lib/backup/constants';
import { exportNotesBackup, serializeBackupJson, validateNotesForExport } from '@/lib/backup/exportBackup';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import { labelFromName } from '@/types/label';
import { createEmptyNote, type Note } from '@/types/note';

let exported: string;
let revoked: string[];
let clicks: number;

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id) || 1, ...overrides });
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

  // ---- Mandatory Test Matrix A & B: Boundary notes counts ----
  it('Matrix A: exports and imports exactly 5,000 and 5,001 notes successfully', () => {
    const set5000: Note[] = Array.from({ length: 5_000 }, (_, i) =>
      note(String(i + 1), { title: `Note ${i + 1}` }),
    );
    const payload5000 = exportPayload(set5000);
    const res5000 = importNotesFromBackup(payload5000, []);
    expect(res5000.result.notesImported).toBe(5_000);

    const set5001: Note[] = Array.from({ length: 5_001 }, (_, i) =>
      note(String(i + 1), { title: `Note ${i + 1}` }),
    );
    const payload5001 = exportPayload(set5001);
    const res5001 = importNotesFromBackup(payload5001, []);
    expect(res5001.result.notesImported).toBe(5_001);
  });

  it('Matrix B: accepts 24,999 and 25,000 notes, and rejects 25,001 notes at boundary', () => {
    const notes24999: Note[] = Array.from({ length: 24_999 }, (_, i) =>
      note(String(i + 1), { title: `N${i}` }),
    );
    expect(() => validateNotesForExport(notes24999)).not.toThrow();

    const notes25000: Note[] = Array.from({ length: 25_000 }, (_, i) =>
      note(String(i + 1), { title: `N${i}` }),
    );
    expect(() => validateNotesForExport(notes25000)).not.toThrow();

    const notes25001: Note[] = Array.from({ length: 25_001 }, (_, i) =>
      note(String(i + 1), { title: `N${i}` }),
    );
    expect(() => validateNotesForExport(notes25001)).toThrow(
      /Cannot export backup: library has 25001 notes \(maximum supported is 25000\)/,
    );
  });

  // ---- Mandatory Test Matrix C & D: File size boundaries & small count with large content ----
  it('Matrix D: small note count with large content exceeding 25 MiB is rejected before download', () => {
    // 300 notes * 90,000 characters = ~27 MB > 25 MiB
    // Each note has 90,000 chars, which is within the 100,000 char per-note limit
    const largeNotes: Note[] = Array.from({ length: 300 }, (_, i) =>
      note(String(i + 1), {
        title: `Large Note ${i + 1}`,
        content: 'A'.repeat(90_000),
      }),
    );

    // Exporter must detect oversized serialized payload and refuse export
    expect(() => exportNotesBackup(largeNotes)).toThrow(/serialized file size/);
  });

  it('Matrix C: serialized backup immediately below and above 25 MiB boundary', () => {
    const rawJustBelow = 'A'.repeat(MAX_BACKUP_FILE_BYTES - 100);
    const payloadBelow = { version: 3, notes: [{ content: rawJustBelow }] };
    // Should serialize fine when under limit
    const jsonBelow = JSON.stringify(payloadBelow, null, 2);
    if (new TextEncoder().encode(jsonBelow).length <= MAX_BACKUP_FILE_BYTES) {
      expect(() => serializeBackupJson(payloadBelow)).not.toThrow();
    }

    const rawJustAbove = 'B'.repeat(MAX_BACKUP_FILE_BYTES + 500);
    const payloadAbove = { version: 3, notes: [{ content: rawJustAbove }] };
    expect(() => serializeBackupJson(payloadAbove)).toThrow(
      /serialized file size .* exceeds maximum supported limit/,
    );
  });

  // ---- Mandatory Test Matrix E: Non-ASCII and multi-byte Unicode content ----
  it('Matrix E: preserves non-ASCII and emoji-heavy content accurately with UTF-8 byte counting', () => {
    const emojiTitle = '🚀 Daily Standup 📝 & Coffee ☕';
    const multilineUnicode = '日本語テスト\n中文测试\n한국어 시험\nالعربية\nעִבְרִית\n✨🔥🎉';
    const unicodeNote = note('99', {
      title: emojiTitle,
      content: multilineUnicode,
      labels: [labelFromName('🏷️ Special & Événements')],
      checklist: [{ id: 'c1', text: '✅ Finish 100% tests 🎉', isChecked: true, position: 0 }],
    });

    const payload = exportPayload([unicodeNote]);
    const { merged, result } = importNotesFromBackup(payload, []);
    expect(result.notesImported).toBe(1);
    expect(result.labelsCreated).toBe(1);

    const imported = merged[0];
    expect(imported.title).toBe(emojiTitle);
    expect(imported.content).toBe(multilineUnicode);
    expect(imported.labels[0].name).toBe('🏷️ Special & Événements');
    expect(imported.checklist[0].text).toBe('✅ Finish 100% tests 🎉');
  });

  // ---- Task 2: Data truncation guards ----
  it('rejects export if any note title exceeds MAX_NOTE_TITLE_CHARS (2,000)', () => {
    const invalidNote = note('1', { title: 'T'.repeat(MAX_NOTE_TITLE_CHARS + 1) });
    expect(() => exportNotesBackup([invalidNote])).toThrow(
      /title exceeds maximum supported length/,
    );
  });

  it('rejects export if any note content exceeds MAX_NOTE_CONTENT_CHARS (100,000)', () => {
    const invalidNote = note('1', { content: 'C'.repeat(MAX_NOTE_CONTENT_CHARS + 1) });
    expect(() => exportNotesBackup([invalidNote])).toThrow(
      /content exceeds maximum supported length/,
    );
  });

  it('rejects export if any note has more than MAX_NOTE_LABELS (100)', () => {
    const labels = Array.from({ length: MAX_NOTE_LABELS + 1 }, (_, i) => labelFromName(`L${i}`));
    const invalidNote = note('1', { labels });
    expect(() => exportNotesBackup([invalidNote])).toThrow(
      /has too many labels/,
    );
  });

  it('rejects export if any note has more than MAX_NOTE_CHECKLIST_ITEMS (500)', () => {
    const checklist = Array.from({ length: MAX_NOTE_CHECKLIST_ITEMS + 1 }, (_, i) => ({
      id: `c${i}`,
      text: `Item ${i}`,
      isChecked: false,
      position: i,
    }));
    const invalidNote = note('1', { checklist });
    expect(() => exportNotesBackup([invalidNote])).toThrow(
      /has too many checklist items/,
    );
  });

  it('rejects export if any checklist item text exceeds MAX_NOTE_TITLE_CHARS (2,000)', () => {
    const invalidNote = note('1', {
      checklist: [{ id: 'c1', text: 'I'.repeat(MAX_NOTE_TITLE_CHARS + 1), isChecked: false, position: 0 }],
    });
    expect(() => exportNotesBackup([invalidNote])).toThrow(
      /checklist item in note .* exceeds maximum supported length/,
    );
  });

  it('rejects export if total unique labels exceed MAX_BACKUP_LABELS (2,000)', () => {
    const notesWithManyLabels: Note[] = Array.from({ length: 25 }, (_, n) =>
      note(String(n + 1), {
        labels: Array.from({ length: 85 }, (__, l) => labelFromName(`N${n}_L${l}`)),
      }),
    );
    // 25 * 85 = 2,125 unique labels > 2,000
    expect(() => exportNotesBackup(notesWithManyLabels)).toThrow(
      /library has 2125 labels \(maximum supported is 2000\)/,
    );
  });
});
