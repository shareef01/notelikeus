import { describe, expect, it } from 'vitest';
import { noteContentKey, notesContentEqual, notesEqual } from '@/lib/notes/noteEquality';
import { labelFromName } from '@/types/label';
import { createEmptyNote, type Note } from '@/types/note';

function note(overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id: '1', localId: 1, timestamp: 1000, ...overrides });
}

describe('notesEqual', () => {
  it('treats identical notes as equal, including across separate objects', () => {
    expect(notesEqual(note(), note())).toBe(true);
  });

  const differences: Array<[string, Partial<Note>]> = [
    ['id', { id: '2' }],
    ['timestamp', { timestamp: 1001 }],
    ['serverUpdatedAt', { serverUpdatedAt: 5 }],
    ['position', { position: 3 }],
    ['color', { color: 0xff112233 | 0 }],
    ['isPinned', { isPinned: true }],
    ['isArchived', { isArchived: true }],
    ['isTrashed', { isTrashed: true }],
    ['reminderTimestamp', { reminderTimestamp: 7 }],
    ['title', { title: 'x' }],
    ['content', { content: 'x' }],
    ['labels', { labels: [labelFromName('Work')] }],
    ['checklist', { checklist: [{ id: 'c1', text: 'milk', isChecked: false, position: 0 }] }],
  ];

  for (const [field, patch] of differences) {
    it(`detects a change to ${field}`, () => {
      expect(notesEqual(note(), note(patch))).toBe(false);
    });
  }

  it('detects a content edit made within the same millisecond', () => {
    expect(notesEqual(note({ content: 'a' }), note({ content: 'b' }))).toBe(false);
  });

  it('detects a checklist item being ticked', () => {
    const item = { id: 'c1', text: 'milk', position: 0 };
    expect(
      notesEqual(
        note({ checklist: [{ ...item, isChecked: false }] }),
        note({ checklist: [{ ...item, isChecked: true }] }),
      ),
    ).toBe(false);
  });

  it('ignores local-only checklist ids', () => {
    expect(
      notesEqual(
        note({ checklist: [{ id: 'local-a', text: 'milk', isChecked: false, position: 0 }] }),
        note({ checklist: [{ id: 'local-b', text: 'milk', isChecked: false, position: 0 }] }),
      ),
    ).toBe(true);
  });

  it('ignores local-only label ids but not label names', () => {
    expect(
      notesEqual(note({ labels: [labelFromName('Work', 'a')] }), note({ labels: [labelFromName('Work', 'b')] })),
    ).toBe(true);
    expect(notesEqual(note({ labels: [labelFromName('Work')] }), note({ labels: [labelFromName('Home')] }))).toBe(
      false,
    );
  });

  it('separates fields so shifted content cannot collide', () => {
    expect(noteContentKey(note({ title: 'ab', content: '' }))).not.toBe(
      noteContentKey(note({ title: 'a', content: 'b' })),
    );
  });
});

describe('notesContentEqual', () => {
  it('is order-independent', () => {
    const a = note({ id: '1', localId: 1 });
    const b = note({ id: '2', localId: 2 });
    expect(notesContentEqual([a, b], [b, a])).toBe(true);
  });

  it('is false when lengths differ', () => {
    expect(notesContentEqual([note()], [])).toBe(false);
  });

  it('is false when any note differs', () => {
    const a = note({ id: '1', localId: 1 });
    const b = note({ id: '2', localId: 2 });
    expect(notesContentEqual([a, b], [a, note({ id: '2', localId: 2, title: 'edited' })])).toBe(false);
  });

  it('does not reorder the caller arrays', () => {
    const a = note({ id: '1', localId: 1 });
    const b = note({ id: '2', localId: 2 });
    const list = [b, a];
    notesContentEqual(list, [a, b]);
    expect(list).toEqual([b, a]);
  });
});
