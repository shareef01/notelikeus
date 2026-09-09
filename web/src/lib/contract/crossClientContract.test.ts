import { describe, expect, it } from 'vitest';
import { readContractFixture } from '@/lib/contract/contractFixtures';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import { exportBackupPayload } from '@/lib/backup/exportBackup';
import { noteToSupabaseRpcArgs, parseTombstoneMap, supabaseNoteToNote } from '@/lib/supabase/supabaseNoteMapper';
import { createEmptyNote, type Note } from '@/types/note';
import { ownerTag } from '@/lib/diagnostics/diagnosticsReport';
import { labelFromName } from '@/types/label';

/**
 * The client-neutral normal form described in `contracts/README.md`. Local row ids are dropped
 * because they are assigned per device and would never match the other client's.
 */
interface NormalizedNote {
  title: string;
  content: string;
  timestamp: number;
  color: number;
  isPinned: boolean;
  isArchived: boolean;
  isTrashed: boolean;
  reminderTimestamp: number | null;
  labels: string[];
  checklist: Array<{ text: string; isChecked: boolean; position: number }>;
}

function normalize(note: Note): NormalizedNote {
  return {
    title: note.title,
    content: note.content,
    timestamp: note.timestamp,
    color: note.color,
    isPinned: note.isPinned,
    isArchived: note.isArchived,
    isTrashed: note.isTrashed,
    reminderTimestamp: note.reminderTimestamp,
    labels: note.labels.map((label) => label.name).sort(),
    checklist: [...note.checklist]
      .sort((a, b) => a.position - b.position)
      .map((item) => ({ text: item.text, isChecked: item.isChecked, position: item.position })),
  };
}

const expectedBackup = readContractFixture<{
  labelsCreated: number;
  notes: NormalizedNote[];
}>('backup/v3-expected-notes.json');

describe('backup v3 contract', () => {
  it('imports a Kotlin-exported backup into the shared normal form', () => {
    const file = readContractFixture('backup/v3-kotlin-export.json');
    const { merged, result } = importNotesFromBackup(file, []);
    expect(merged.map(normalize)).toEqual(expectedBackup.notes);
    expect(result.labelsCreated).toBe(expectedBackup.labelsCreated);
  });

  it('imports its own export into the same normal form', () => {
    const file = readContractFixture('backup/v3-web-export.json');
    const { merged, result } = importNotesFromBackup(file, []);
    expect(merged.map(normalize)).toEqual(expectedBackup.notes);
    expect(result.labelsCreated).toBe(expectedBackup.labelsCreated);
  });

  it('exports the shape the Kotlin importer parses', () => {
    // Built from the fixture rather than hand-written, so the assertion is about this client's
    // serializer and not about a second copy of the fixture drifting.
    const web = readContractFixture<{
      version: number;
      labels: unknown[];
      notes: Record<string, unknown>[];
    }>('backup/v3-web-export.json');
    const notes: Note[] = web.notes.map((entry, index) =>
      createEmptyNote({
        id: String(entry.id),
        localId: entry.id as number,
        title: entry.title as string,
        content: entry.content as string,
        timestamp: entry.timestamp as number,
        color: entry.color as number,
        isPinned: entry.isPinned as boolean,
        isArchived: entry.isArchived as boolean,
        isTrashed: entry.isTrashed as boolean,
        position: entry.position as number,
        reminderTimestamp: (entry.reminderTimestamp as number | undefined) ?? null,
        labels: (entry.labels as string[]).map((name) => labelFromName(name)),
        checklist: (
          entry.checklist as Array<{ text: string; isChecked: boolean; position: number }>
        ).map((item, itemIndex) => ({ id: `chk-${index}-${itemIndex}`, ...item })),
      }),
    );

    const payload = exportBackupPayload(notes) as Record<string, unknown> & {
      notes: unknown[];
      labels: unknown[];
    };
    expect(payload.version).toBe(web.version);
    expect(payload.app).toBe('Notelikeus');
    expect(payload.notes).toEqual(web.notes);
    expect(payload.labels).toEqual(web.labels);
  });
});

describe('cloud mapper contract', () => {
  it('parses a full note row into the shared normal form', () => {
    const fixture = readContractFixture<{ row: never; expected: NormalizedNote & { localId: number; position: number; serverUpdatedAt: number } }>(
      'cloud/note-row.json',
    );
    const note = supabaseNoteToNote(fixture.row);
    expect({ ...normalize(note), localId: note.localId, position: note.position, serverUpdatedAt: note.serverUpdatedAt }).toEqual(
      fixture.expected,
    );
  });

  it('parses a sparse note row with the shared fallbacks', () => {
    const fixture = readContractFixture<{ row: never; expected: NormalizedNote & { localId: number; position: number; serverUpdatedAt: number } }>(
      'cloud/note-row-sparse.json',
    );
    const note = supabaseNoteToNote(fixture.row);
    expect({ ...normalize(note), localId: note.localId, position: note.position, serverUpdatedAt: note.serverUpdatedAt }).toEqual(
      fixture.expected,
    );
  });

  it('builds the apply_note_change argument object', () => {
    const fixture = readContractFixture<{ args: Record<string, unknown> }>(
      'cloud/note-rpc-args.json',
    );
    const row = readContractFixture<{ row: never }>('cloud/note-row.json');
    const note = supabaseNoteToNote(row.row);
    expect(noteToSupabaseRpcArgs(note, null)).toEqual(fixture.args);
  });

  it('derives the tombstone map', () => {
    const fixture = readContractFixture<{ rows: never[]; expected: Record<string, number> }>(
      'cloud/tombstone-row.json',
    );
    expect(parseTombstoneMap(fixture.rows)).toEqual(fixture.expected);
  });
});

describe('diagnostics contract', () => {
  it('derives the same redacted account tag as the Kotlin clients', () => {
    // A user troubleshooting on a phone and in a browser must produce reports that can be matched
    // to each other and told apart from someone else's. Both sides read this one file.
    const fixture = readContractFixture<{
      vectors: Array<{ ownerId: string; tag: string }>;
    }>('diagnostics/owner-tag-vectors.json');

    for (const vector of fixture.vectors) {
      expect(ownerTag(vector.ownerId), `tag for "${vector.ownerId}"`).toBe(vector.tag);
    }
    expect(ownerTag(null)).toBe('none');
  });

  it('never lets the account id itself into the tag', () => {
    const fixture = readContractFixture<{
      vectors: Array<{ ownerId: string; tag: string }>;
    }>('diagnostics/owner-tag-vectors.json');

    for (const vector of fixture.vectors) {
      if (!vector.ownerId || vector.ownerId === '__guest__') continue;
      expect(vector.tag).not.toContain(vector.ownerId);
    }
  });
});
