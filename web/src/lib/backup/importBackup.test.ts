import { describe, expect, it } from 'vitest';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import {
  BACKUP_VERSION,
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
});
