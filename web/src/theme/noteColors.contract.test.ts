import { describe, expect, it } from 'vitest';
import { readContractFixture } from '@/lib/contract/contractFixtures';
import { NOTE_COLOR_KEYWORDS } from '@/lib/query/noteQueryMatch';
import { cloudMapToNote } from '@/lib/mappers/noteCloudMapper';
import { supabaseNoteToNote } from '@/lib/supabase/supabaseNoteMapper';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import { BACKUP_VERSION } from '@/lib/backup/constants';
import { createEmptyNote } from '@/types/note';
import { labelFromName } from '@/types/label';
import { NO_NOTE_COLOR, NOTE_COLOR_NAMES, NOTE_COLOR_OPTIONS } from '@/theme/colors';

/**
 * contracts/design/note-colors.json against theme/colors.ts. Kotlin NoteColorsContractTest asserts
 * the same file against NOTE_COLOR_OPTIONS, NOTE_COLOR_KEYWORDS and strings.xml, so a swatch has one
 * name, one keyword and one ARGB on every client.
 */
interface NoteColorEntry {
  index: number;
  keyword: string;
  name: string;
  light: string;
  dark: string;
}

const contract = readContractFixture<{ noColor: string; colors: NoteColorEntry[] }>(
  'design/note-colors.json',
);

const argb = (hex: string) => Number.parseInt(hex, 16) | 0;

describe('note colour contract', () => {
  it('lists every palette entry in order', () => {
    expect(contract.colors.map((entry) => entry.index)).toEqual(NOTE_COLOR_OPTIONS.map((_, i) => i));
  });

  it('matches the stored ARGB values', () => {
    expect(NOTE_COLOR_OPTIONS).toEqual(
      contract.colors.map((entry) => ({ light: argb(entry.light), dark: argb(entry.dark) })),
    );
  });

  it('matches the color: search keywords', () => {
    expect([...NOTE_COLOR_KEYWORDS]).toEqual(contract.colors.map((entry) => entry.keyword));
  });

  it('uses the native names', () => {
    expect(NOTE_COLOR_NAMES).toEqual(contract.colors.map((entry) => entry.name));
  });
});

describe('a note with no stored colour', () => {
  const noColor = argb(contract.noColor);

  it('is the palette’s no-colour entry', () => {
    expect(NO_NOTE_COLOR).toBe(noColor);
    expect(NOTE_COLOR_OPTIONS[0]).toEqual({ light: noColor, dark: noColor });
  });

  it('reads as no colour from a cloud row', () => {
    const note = supabaseNoteToNote({ note_id: '7', title: 'Plain' } as Parameters<typeof supabaseNoteToNote>[0]);
    expect(note.color).toBe(noColor);
  });

  it('reads as no colour from a cloud document', () => {
    expect(cloudMapToNote('7', { title: 'Plain' }, labelFromName).color).toBe(noColor);
  });

  it('reads as no colour from a backup', () => {
    const { merged } = importNotesFromBackup(
      { version: BACKUP_VERSION, notes: [{ title: 'Plain', content: '', timestamp: 1 }] },
      [],
    );
    expect(merged.map((note) => note.color)).toEqual([noColor]);
  });

  it('is what a new note starts with', () => {
    expect(createEmptyNote({ id: '1', localId: 1 }).color).toBe(noColor);
  });
});
