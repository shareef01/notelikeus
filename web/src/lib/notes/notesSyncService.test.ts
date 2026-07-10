import { describe, expect, it } from 'vitest';
import type { Note } from '@/types/note';
import { applyRemoteSnapshot } from '@/lib/notes/notesSyncService';

const note = (partial: Partial<Note>): Note => ({
  id: '1',
  localId: 1,
  cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
  title: 'Local',
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

describe('applyRemoteSnapshot', () => {
  it('does not overwrite locked local notes with remote data', () => {
    const cloudId = 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee';
    const local = note({ cloudId, isLocked: true, title: 'Secret', timestamp: 50 });
    const remote = note({ cloudId, title: 'Remote leak', timestamp: 200, id: '9', localId: 9 });

    const merged = applyRemoteSnapshot([local], [remote], new Set());

    expect(merged).toHaveLength(1);
    expect(merged[0]?.title).toBe('Secret');
    expect(merged[0]?.isLocked).toBe(true);
  });
});
