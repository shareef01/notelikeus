import { describe, expect, it } from 'vitest';
import { existsSync, writeFileSync } from 'node:fs';
import { readContractFixture, contractFixturePath, readContractBinaryFixture } from '@/lib/contract/contractFixtures';
import { buildBackupBundle, parseBackupBundle } from '@/lib/backup/bundle/backupBundle';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import { exportBackupPayload } from '@/lib/backup/exportBackup';
import {
  MAX_BACKUP_LABELS,
  MAX_BACKUP_NOTES,
  MAX_JSON_DEPTH,
  MAX_NOTE_CHECKLIST_ITEMS,
  MAX_NOTE_CONTENT_CHARS,
  MAX_NOTE_LABELS,
  MAX_NOTE_TITLE_CHARS,
} from '@/lib/backup/constants';
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

  it('imports the rich cross-platform contract fixture with Unicode and max field limits (Phase A3)', () => {
    const richFile = readContractFixture('backup/v3-cross-platform-rich.json');
    const { merged, result } = importNotesFromBackup(richFile, []);

    expect(result.notesImported).toBe(3);
    expect(result.labelsCreated).toBe(3);
    expect(merged).toHaveLength(3);

    // Note 1: Unicode and emojis
    const n1 = merged[0]!;
    expect(n1.title).toBe('🚀 Daily Standup 📝 & Coffee ☕');
    expect(n1.content).toBe('日本語テスト\n中文测试\n한국어 시험\nالعربية\nעִבְרִית\n✨🔥🎉');
    expect(n1.checklist).toHaveLength(2);
    expect(n1.checklist[0]!.text).toBe('Item 1 (checked) 🍱');
    expect(n1.checklist[0]!.isChecked).toBe(true);
    expect(n1.labels.map((l) => l.name).sort()).toEqual(['Personal', '日本語ラベル']);
    expect(n1.reminderTimestamp).toBe(4102444800000);

    // Note 2: Maximum field limits (2,000 title, 100,000 content, 500 checklist items)
    const n2 = merged[1]!;
    expect(n2.title).toHaveLength(2000);
    expect(n2.content).toHaveLength(100000);
    expect(n2.checklist).toHaveLength(500);
    expect(n2.labels.map((l) => l.name)).toEqual(['Work']);

    // Note 3: Minimal note with past reminder
    const n3 = merged[2]!;
    expect(n3.title).toBe('Minimal Note');
    expect(n3.content).toBe('');
    // Past reminders are normalized to null on import by design
    expect(n3.reminderTimestamp).toBeNull();
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

  it('generates and validates cross-runtime v4 .nlkbak bundle for Kotlin consumer', async () => {
    const sampleBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4]);
    const archive = await buildBackupBundle(
      {
        version: 3,
        app: 'Notelikeus',
        appVersion: '2.4.1 (web)',
        exportedAt: 1767225600000,
        labels: [{ id: 1, name: 'WebLabel' }],
        notes: [
          {
            id: 1,
            title: 'From Web Exporter',
            content: 'Cross-runtime bundle test content 🚀',
            timestamp: 1767225000000,
            color: 0,
            isPinned: false,
            isArchived: false,
            isTrashed: false,
            position: 0,
            reminderTimestamp: 4102444800000,
            labels: ['WebLabel'],
            checklist: [{ text: 'Item 1', isChecked: true, position: 0 }],
          },
        ],
      },
      [
        {
          noteId: 1,
          attachmentId: 'att-cross-1',
          type: 'image',
          mimeType: 'image/png',
          bytes: sampleBytes,
        },
      ],
      { app: 'Notelikeus', appVersion: '2.4.1 (web)', exportedAt: 1767225600000 },
    );

    writeFileSync(contractFixturePath('backup/v4-web-bundle.nlkbak'), archive);

    // Verify self-parse
    const parsed = await parseBackupBundle(archive);
    expect(parsed.manifest.formatVersion).toBe(4);
    expect(parsed.manifest.app).toBe('Notelikeus');
    expect(parsed.media.has('att-cross-1')).toBe(true);
    expect(parsed.media.get('att-cross-1')!.bytes).toEqual(sampleBytes);
  });

  it('parses genuine Kotlin-exported v4 .nlkbak bundle if present', async () => {
    const kotlinBundlePath = contractFixturePath('backup/v4-kotlin-bundle.nlkbak');
    if (!existsSync(kotlinBundlePath)) return;

    const archive = readContractBinaryFixture('backup/v4-kotlin-bundle.nlkbak');
    const parsed = await parseBackupBundle(archive);
    expect(parsed.manifest.formatVersion).toBe(4);
    expect(parsed.manifest.app).toBe('Notelikeus');
    expect(parsed.media.has('att-cross-1')).toBe(true);
  });

  it('keeps import soft caps aligned with the shared fixture and Kotlin', () => {
    const limits = readContractFixture<{
      maxJsonDepth: number;
      maxBackupNotes: number;
      maxBackupLabels: number;
      maxNoteTitleChars: number;
      maxNoteContentChars: number;
      maxNoteChecklistItems: number;
      maxNoteLabels: number;
    }>('backup/import-limits.json');

    expect(MAX_JSON_DEPTH).toBe(limits.maxJsonDepth);
    expect(MAX_BACKUP_NOTES).toBe(limits.maxBackupNotes);
    expect(MAX_BACKUP_LABELS).toBe(limits.maxBackupLabels);
    expect(MAX_NOTE_TITLE_CHARS).toBe(limits.maxNoteTitleChars);
    expect(MAX_NOTE_CONTENT_CHARS).toBe(limits.maxNoteContentChars);
    expect(MAX_NOTE_CHECKLIST_ITEMS).toBe(limits.maxNoteChecklistItems);
    expect(MAX_NOTE_LABELS).toBe(limits.maxNoteLabels);
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
    expect(noteToSupabaseRpcArgs(note, null, 0)).toEqual(fixture.args);
    // restore_note has no attachment-id parameter.
    const { p_attachment_ids: _ids, ...restoreArgs } = fixture.args;
    expect(noteToSupabaseRpcArgs(note, null, 0, { attachmentIds: false })).toEqual(restoreArgs);
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
