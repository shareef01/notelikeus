import { describe, expect, it } from 'vitest';
import { notesEligibleForReminders } from '@/lib/reminders/reminderScheduler';
import type { Note } from '@/types/note';

const note = (partial: Partial<Note>): Note => ({
  id: '1',
  localId: 1,
  cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
  title: 'Reminder',
  content: 'Body',
  timestamp: 100,
  color: 0,
  isPinned: false,
  isArchived: false,
  isTrashed: false,
  position: 0,
  isLocked: false,
  reminderTimestamp: Date.now() + 60_000,
  labels: [],
  attachments: [],
  checklist: [],
  ...partial,
});

describe('notesEligibleForReminders', () => {
  it('includes notes with future reminders', () => {
    const eligible = notesEligibleForReminders([note({})]);
    expect(eligible).toHaveLength(1);
  });

  it('excludes locked notes', () => {
    const eligible = notesEligibleForReminders([note({ isLocked: true })]);
    expect(eligible).toHaveLength(0);
  });

  it('excludes trashed notes', () => {
    const eligible = notesEligibleForReminders([note({ isTrashed: true })]);
    expect(eligible).toHaveLength(0);
  });
});
