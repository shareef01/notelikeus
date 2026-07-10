import { describe, expect, it } from 'vitest';
import { buildNotesBackupPayload } from '@/lib/backup/exportBackup';
import type { Note } from '@/types/note';

const sampleNote = (partial: Partial<Note> = {}): Note => ({
  id: '1',
  localId: 1,
  cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
  title: 'Secret',
  content: 'Hidden',
  timestamp: 1000,
  color: 0,
  isPinned: false,
  isArchived: false,
  isTrashed: false,
  position: 0,
  isLocked: false,
  reminderTimestamp: null,
  labels: [{ id: 'label-work', name: 'Work' }],
  attachments: [],
  checklist: [{ id: 'chk-1', text: 'Item', isChecked: false, position: 0 }],
  ...partial,
});

describe('buildNotesBackupPayload', () => {
  it('redacts locked note content', () => {
    const payload = buildNotesBackupPayload([
      sampleNote({ isLocked: true, title: 'Secret', content: 'Hidden body' }),
    ]);

    expect(payload.notes).toHaveLength(1);
    const entry = payload.notes[0] as {
      isLocked?: boolean;
      title?: string;
      content?: string;
      labels?: unknown[];
      checklist?: unknown[];
    };
    expect(entry.isLocked).toBe(true);
    expect(entry.title).toBe('');
    expect(entry.content).toBe('');
    expect(entry.labels).toEqual([]);
    expect(entry.checklist).toEqual([]);
  });

  it('exports unlocked notes with labels', () => {
    const payload = buildNotesBackupPayload([sampleNote({ title: 'Open', content: 'Body' })]);
    const entry = payload.notes[0] as { title?: string; labels?: string[] };
    expect(entry.title).toBe('Open');
    expect(entry.labels).toEqual(['Work']);
    expect(payload.labels).toHaveLength(1);
  });

  it('omits labels from locked notes in label list', () => {
    const payload = buildNotesBackupPayload([
      sampleNote({ isLocked: true, labels: [{ id: 'label-private', name: 'Private' }] }),
    ]);
    expect(payload.labels).toHaveLength(0);
  });
});
