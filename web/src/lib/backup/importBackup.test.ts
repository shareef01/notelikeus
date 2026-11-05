import { describe, expect, it } from 'vitest';
import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';
import {
  BACKUP_VERSION,
  MAX_BACKUP_FILE_BYTES,
  MAX_BACKUP_NOTES,
  MAX_BACKUP_LABELS,
  MAX_NOTE_CHECKLIST_ITEMS,
  MAX_NOTE_CONTENT_CHARS,
  MAX_NOTE_TITLE_CHARS,
} from '@/lib/backup/constants';

/**
 * The import contract, pinned so it cannot drift silently.
 *
 * Import means "add these notes as new copies", not "restore this device to the backup". Every
 * imported note gets a fresh local identity and nothing existing is replaced or removed, so
 * importing the same file twice deliberately yields two sets. Attachments are outside the JSON
 * format's scope and are not carried across.
 */
describe('backup import semantics', () => {
  const backup = {
    version: BACKUP_VERSION,
    notes: [
      { title: 'Groceries', content: 'milk', timestamp: 1_725_000_000 },
      { title: 'Ideas', content: 'a second note', timestamp: 1_725_000_001 },
    ],
  };

  it('imports notes as new copies rather than replacing what is already there', () => {
    const existing = importNotesFromBackup(backup, []).merged;
    expect(existing).toHaveLength(2);

    const { merged, result } = importNotesFromBackup(backup, existing);

    // Append, never overwrite: the originals survive alongside the new copies.
    expect(result.notesImported).toBe(2);
    expect(merged).toHaveLength(4);
    expect(merged.filter((note) => note.title === 'Groceries')).toHaveLength(2);
  });

  it('gives every imported note a distinct local identity', () => {
    const first = importNotesFromBackup(backup, []).merged;
    const { merged } = importNotesFromBackup(backup, first);

    const ids = merged.map((note) => note.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('does not carry attachments, which the JSON format does not contain', () => {
    const { merged } = importNotesFromBackup(
      {
        version: BACKUP_VERSION,
        notes: [
          {
            title: 'Has an attachment upstream',
            content: '',
            timestamp: 1,
            attachments: [{ id: 'att-1', storagePath: 'r2:owners/x/notes/1/att-1' }],
          },
        ],
      },
      [],
    );

    expect(merged[0].attachments).toEqual([]);
  });
});

describe('importNotesFromBackup', () => {
  it('coerces non-string fields instead of putting them in the store', () => {
    const { merged, result } = importNotesFromBackup(
      {
        version: 3,
        notes: [{ title: { evil: true }, content: 42, isPinned: 'yes', timestamp: 'nope' }],
      },
      [],
    );

    expect(result.notesImported).toBe(1);
    const note = merged[0];
    expect(typeof note.title).toBe('string');
    expect(typeof note.content).toBe('string');
    expect(note.title).toBe('');
    expect(note.content).toBe('');
    expect(note.isPinned).toBe(false);
    expect(Number.isFinite(note.timestamp)).toBe(true);
    // The whole point: a search over imported notes must not throw.
    expect(() => note.title.toLowerCase()).not.toThrow();
  });

  it('clamps fields to the limits apply_note_change enforces, so imports stay syncable', () => {
    const { merged } = importNotesFromBackup(
      {
        version: 3,
        notes: [
          {
            title: 'a'.repeat(MAX_NOTE_TITLE_CHARS + 500),
            content: 'b'.repeat(MAX_NOTE_CONTENT_CHARS + 500),
            checklist: Array.from({ length: MAX_NOTE_CHECKLIST_ITEMS + 50 }, (_, i) => ({
              text: `item ${i}`,
              isChecked: false,
              position: i,
            })),
          },
        ],
      },
      [],
    );

    const note = merged[0];
    expect(note.title).toHaveLength(MAX_NOTE_TITLE_CHARS);
    expect(note.content).toHaveLength(MAX_NOTE_CONTENT_CHARS);
    expect(note.checklist).toHaveLength(MAX_NOTE_CHECKLIST_ITEMS);
  });

  it('drops malformed label entries rather than stringifying them', () => {
    const { merged } = importNotesFromBackup(
      {
        version: 3,
        notes: [{ title: 'Note', labels: [{ name: { nested: 1 } }, 'work', { name: 'home' }] }],
      },
      [],
    );

    expect(merged[0].labels.map((label) => label.name)).toEqual(['work', 'home']);
  });

  it('survives entries that are not objects at all', () => {
    const { merged, result } = importNotesFromBackup(
      { version: 3, notes: [null, 'string-note', 7, { title: 'Real' }] },
      [],
    );

    expect(result.notesImported).toBe(1);
    expect(merged[0].title).toBe('Real');
  });

  it('rejects a backup newer than this build understands', () => {
    expect(() => importNotesFromBackup({ version: 99, notes: [] }, [])).toThrow(
      /Unsupported backup version/,
    );
  });

  /**
   * Parity with Kotlin's `NoteBackupImporter.MAX_BACKUP_LABELS`. The per-note label cap does not
   * bound the root array, so a file that spends its whole size budget on label strings built an
   * unbounded Set before any note was read.
   */
  it('caps how many distinct labels one backup can introduce', () => {
    const labels = Array.from({ length: MAX_BACKUP_LABELS + 500 }, (_, i) => `label-${i}`);

    const { result } = importNotesFromBackup(
      { version: BACKUP_VERSION, notes: [{ localId: 1, title: 'Note' }], labels },
      [],
    );

    expect(result.labelsCreated).toBe(MAX_BACKUP_LABELS);
  });

  it('rejects a labels object instead of throwing TypeError', () => {
    expect(() =>
      importNotesFromBackup(
        { version: 3, labels: { name: 'not-an-array' }, notes: [{ title: 'n' }] },
        [],
      ),
    ).toThrow(/labels must be an array/);
  });

  // AUDIT-010: a note's own labels were not checked. An object or a number threw a raw TypeError,
  // and a string was read character by character into one-letter labels. Kotlin refuses the file.
  it.each([
    ['an object', { name: 'Work' }],
    ['a number', 42],
    ['a string', 'Work'],
  ])("rejects a note whose labels is %s, as Kotlin does", (_kind, labels) => {
    expect(() =>
      importNotesFromBackup({ version: 3, notes: [{ title: 'n', labels }] }, []),
    ).toThrow(/^Note labels must be an array$/);
  });

  it('still imports a note with no labels or null labels', () => {
    const { result } = importNotesFromBackup(
      { version: 3, notes: [{ title: 'a' }, { title: 'b', labels: null }] },
      [],
    );
    expect(result.notesImported).toBe(2);
  });

  it('rejects negative, fractional, and non-finite versions', () => {
    for (const version of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(() => importNotesFromBackup({ version, notes: [{ title: 'n' }] }, [])).toThrow(
        /Invalid backup version/,
      );
    }
  });

  it('accepts legacy version 0', () => {
    const { result } = importNotesFromBackup(
      { version: 0, notes: [{ title: 'Legacy', content: 'ok', timestamp: 1, color: 0 }] },
      [],
    );
    expect(result.notesImported).toBe(1);
  });

  it('still imports every label of an ordinary backup', () => {
    const { result } = importNotesFromBackup(
      { version: BACKUP_VERSION, notes: [{ localId: 1, title: 'Note' }], labels: ['work', 'home'] },
      [],
    );

    expect(result.labelsCreated).toBe(2);
  });

  it('rejects a backup that exceeds MAX_BACKUP_NOTES', () => {
    const tooMany = Array.from({ length: MAX_BACKUP_NOTES + 1 }, (_, i) => ({
      localId: i + 1,
      title: `Note ${i}`,
    }));
    expect(() =>
      importNotesFromBackup({ version: BACKUP_VERSION, notes: tooMany }, []),
    ).toThrow(/Backup has too many notes \(max 25000\)/);
  });

  it('accepts a backup at the MAX_BACKUP_NOTES boundary', () => {
    const boundary = Array.from({ length: 6_000 }, (_, i) => ({
      localId: i + 1,
      title: `Note ${i + 1}`,
      content: 'Large backup test content',
    }));
    const { result, merged } = importNotesFromBackup(
      { version: BACKUP_VERSION, notes: boundary },
      [],
    );
    expect(result.notesImported).toBe(6_000);
    expect(merged).toHaveLength(6_000);
  });

  it('readBackupFile rejects a file exceeding MAX_BACKUP_FILE_BYTES', async () => {
    const fakeOversizedFile = {
      size: MAX_BACKUP_FILE_BYTES + 1,
      text: async () => '{"version": 3, "notes": []}',
    } as unknown as File;

    await expect(readBackupFile(fakeOversizedFile)).rejects.toThrow(/Backup file is too large/);
  });

  it('readBackupFile parses a valid backup file within MAX_BACKUP_FILE_BYTES', async () => {
    const validFile = {
      size: 50,
      text: async () => JSON.stringify({ version: BACKUP_VERSION, notes: [{ title: 'hello' }] }),
    } as unknown as File;

    const parsed = (await readBackupFile(validFile)) as { version: number; notes: unknown[] };
    expect(parsed.version).toBe(BACKUP_VERSION);
    expect(parsed.notes).toHaveLength(1);
  });

  describe('cross-platform limit consistency (Task 3)', () => {
    it('readBackupFile accepts file exactly at the 25 MiB byte boundary', async () => {
      const exactFile = {
        size: MAX_BACKUP_FILE_BYTES,
        text: async () => JSON.stringify({ version: BACKUP_VERSION, notes: [] }),
      } as unknown as File;

      const parsed = await readBackupFile(exactFile);
      expect((parsed as any).version).toBe(BACKUP_VERSION);
    });

    it('readBackupFile accepts ASCII file with 25_500_000 characters within 25 MiB limit', async () => {
      // 25,500,000 ASCII characters is > 25,000,000 chars, but < 26,214,400 bytes (25 MiB)
      const file25_5M = {
        size: 25_500_000,
        text: async () => JSON.stringify({ version: BACKUP_VERSION, notes: [] }),
      } as unknown as File;

      const parsed = await readBackupFile(file25_5M);
      expect((parsed as any).version).toBe(BACKUP_VERSION);
    });

    it('readBackupFile rejects file at 25 MiB + 1 byte', async () => {
      const overFile = {
        size: MAX_BACKUP_FILE_BYTES + 1,
        text: async () => JSON.stringify({ version: BACKUP_VERSION, notes: [] }),
      } as unknown as File;

      await expect(readBackupFile(overFile)).rejects.toThrow('Backup file is too large');
    });

    it('handles multibyte CJK, emojis, and surrogate pairs cleanly', () => {
      const cjkAndEmojiBackup = {
        version: BACKUP_VERSION,
        notes: [
          {
            title: '漢字タイトル 🍱',
            content: '日本語・中文・한국어 ✨🔥🎉',
            labels: ['ラベル1', '标签2'],
            checklist: [{ text: 'タスク 🚀', isChecked: true, position: 0 }],
          },
        ],
      };
      const { result, merged } = importNotesFromBackup(cjkAndEmojiBackup, []);
      expect(result.notesImported).toBe(1);
      expect(merged[0]!.title).toBe('漢字タイトル 🍱');
      expect(merged[0]!.content).toBe('日本語・中文・한국어 ✨🔥🎉');
      expect(merged[0]!.checklist[0]!.text).toBe('タスク 🚀');
    });

    it('rejects truncated / invalid JSON input', async () => {
      const truncatedFile = {
        size: 100,
        text: async () => '{"version": 3, "notes": [{"title": "Trunc',
      } as unknown as File;

      await expect(readBackupFile(truncatedFile)).rejects.toThrow('Backup file is not valid JSON');
    });

    it('enforces note count boundary: 25,000 accepted, 25,001 rejected', () => {
      const notes25k = Array.from({ length: 25_000 }, (_, i) => ({ title: `Note ${i}` }));
      const { result } = importNotesFromBackup({ version: BACKUP_VERSION, notes: notes25k }, []);
      expect(result.notesImported).toBe(25_000);

      const notes25k1 = Array.from({ length: 25_001 }, (_, i) => ({ title: `Note ${i}` }));
      expect(() =>
        importNotesFromBackup({ version: BACKUP_VERSION, notes: notes25k1 }, []),
      ).toThrow('Backup has too many notes (max 25000)');
    });
  });
});
