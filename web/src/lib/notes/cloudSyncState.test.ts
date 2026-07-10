import { describe, expect, it } from 'vitest';
import { applyRemoteDeletions } from '@/lib/notes/cloudSyncState';
import type { Note } from '@/types/note';

const note = (partial: Partial<Note>): Note => ({
  id: '1',
  localId: 1,
  cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
  title: 'A',
  content: '',
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

describe('applyRemoteDeletions', () => {
  it('removes notes deleted remotely when they were previously known in cloud', () => {
    const deletedId = 'bbbbbbbb-bbbb-4ccc-dddd-eeeeeeeeeeee';
    const keptId = 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee';
    const result = applyRemoteDeletions(
      [note({ cloudId: deletedId }), note({ cloudId: keptId, id: '2', localId: 2 })],
      new Set([deletedId, keptId]),
      new Set([keptId]),
    );
    expect(result).toHaveLength(1);
    expect(result[0]?.cloudId).toBe(keptId);
  });

  it('keeps locked notes even when removed from cloud snapshot', () => {
    const lockedId = 'cccccccc-cccc-4ccc-dddd-eeeeeeeeeeee';
    const result = applyRemoteDeletions(
      [note({ cloudId: lockedId, isLocked: true, title: 'Secret' })],
      new Set([lockedId]),
      new Set(),
    );
    expect(result).toHaveLength(1);
  });
});
