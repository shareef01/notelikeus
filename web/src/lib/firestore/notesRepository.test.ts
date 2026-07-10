import { describe, expect, it } from 'vitest';
import { mergeRemoteNotes } from '@/lib/firestore/notesRepository';
import type { Note } from '@/types/note';

const baseNote = (partial: Partial<Note>): Note => ({
  id: '1',
  localId: 1,
  cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
  title: 'A',
  content: 'Body',
  timestamp: 100,
  color: 0,
  isPinned: false,
  isArchived: false,
  isTrashed: false,
  position: 0,
  isLocked: false,
  reminderTimestamp: null,
  labels: [],
  attachments: [],
  checklist: [],
  ...partial,
});

describe('mergeRemoteNotes', () => {
  it('matches notes by cloudId across different local ids', async () => {
    const local = baseNote({ id: '10', localId: 10, timestamp: 50 });
    const remote = baseNote({ id: '99', localId: 99, timestamp: 200, title: 'Remote wins' });
    const merged = await mergeRemoteNotes([local], [remote]);
    expect(merged).toHaveLength(1);
    expect(merged[0]?.title).toBe('Remote wins');
    expect(merged[0]?.id).toBe('10');
    expect(merged[0]?.localId).toBe(10);
  });

  it('does not overwrite locked local notes', async () => {
    const local = baseNote({ isLocked: true, title: 'Secret', timestamp: 50 });
    const remote = baseNote({ timestamp: 200, title: 'Remote' });
    const merged = await mergeRemoteNotes([local], [remote]);
    expect(merged).toHaveLength(1);
    expect(merged[0]?.title).toBe('Secret');
  });
});
