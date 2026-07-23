import { describe, expect, it } from 'vitest';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import {
  MAX_NOTE_CHECKLIST_ITEMS,
  MAX_NOTE_CONTENT_CHARS,
  MAX_NOTE_TITLE_CHARS,
} from '@/lib/backup/constants';

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

  it('clamps fields to the limits firestore.rules enforces, so imports stay syncable', () => {
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
});
