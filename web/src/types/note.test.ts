import { afterEach, describe, expect, it, vi } from 'vitest';
import { labelFromName } from '@/types/label';
import {
  allocateLocalNoteId,
  createEmptyNote,
  filterNotes,
  nextLocalNoteIdAfter,
  type Note,
} from '@/types/note';
import { NOTE_COLOR_OPTIONS } from '@/theme/colors';

function note(partial: Partial<Note> & Pick<Note, 'id'>): Note {
  return createEmptyNote({ localId: Number(partial.id) || 1, ...partial });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('createEmptyNote', () => {
  it('fills defaults and keeps the supplied identity', () => {
    const created = createEmptyNote({ id: '7', localId: 7 });
    expect(created).toMatchObject({
      id: '7',
      localId: 7,
      title: '',
      content: '',
      isPinned: false,
      isArchived: false,
      isTrashed: false,
      position: 0,
      reminderTimestamp: null,
      serverUpdatedAt: null,
      labels: [],
      attachments: [],
      checklist: [],
    });
  });

  it('lets the caller override fields without losing identity', () => {
    const created = createEmptyNote({ id: '7', localId: 7, title: 'hi', isPinned: true });
    expect(created).toMatchObject({ id: '7', localId: 7, title: 'hi', isPinned: true });
  });
});

describe('local id allocation', () => {
  it('allocates above the current max', () => {
    expect(allocateLocalNoteId([note({ id: '1' }), note({ id: '2' })])).toBeGreaterThan(2);
    expect(nextLocalNoteIdAfter(5)).toBeGreaterThan(5);
  });

  it('never reuses the max even when the clock is behind it', () => {
    const huge = Number.MAX_SAFE_INTEGER - 10;
    expect(nextLocalNoteIdAfter(huge)).toBe(huge + 1);
  });

  it('does not collide for two allocations in the same millisecond', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2025-07-12T16:37:00Z'));
    const ids = new Set(Array.from({ length: 50 }, () => nextLocalNoteIdAfter(0)));
    expect(ids.size).toBeGreaterThan(1);
  });
});

describe('filterNotes', () => {
  const active = note({ id: '1', title: 'groceries', timestamp: 100, position: 1 });
  const archived = note({ id: '2', title: 'old plan', isArchived: true, timestamp: 200 });
  const trashed = note({ id: '3', title: 'junk', isTrashed: true, timestamp: 300 });
  const all = [active, archived, trashed];

  it('separates active, archived and trashed views', () => {
    expect(filterNotes(all, { filter: 'active' }).map((n) => n.id)).toEqual(['1']);
    expect(filterNotes(all, { filter: 'archived' }).map((n) => n.id)).toEqual(['2']);
    expect(filterNotes(all, { filter: 'trashed' }).map((n) => n.id)).toEqual(['3']);
  });

  it('searches title, content, checklist text and labels case-insensitively', () => {
    const notes = [
      note({ id: '1', title: 'Shopping' }),
      note({ id: '2', content: 'buy MILK' }),
      note({
        id: '3',
        checklist: [{ id: 'c1', text: 'Bread', isChecked: false, position: 0 }],
      }),
      note({ id: '4', labels: [labelFromName('Work')] }),
      note({ id: '5', title: 'unrelated' }),
    ];
    const ids = (query: string) =>
      filterNotes(notes, { filter: 'active', searchQuery: query }).map((n) => n.id);
    expect(ids('shop')).toEqual(['1']);
    expect(ids('milk')).toEqual(['2']);
    expect(ids('bread')).toEqual(['3']);
    expect(ids('work')).toEqual(['4']);
    expect(ids('   ')).toHaveLength(5);
  });

  it('matches token prefixes and ignores diacritics, like the native client', () => {
    const notes = [
      note({ id: '1', title: 'notes' }),
      note({ id: '2', title: 'Café Zürich' }),
      note({ id: '3', title: 'Bread and milk' }),
    ];
    const ids = (query: string) =>
      filterNotes(notes, { filter: 'active', searchQuery: query }).map((n) => n.id);
    expect(ids('not')).toEqual(['1']);
    expect(ids('ote')).toEqual([]);
    expect(ids('cafe')).toEqual(['2']);
    expect(ids('milk bread')).toEqual(['3']);
  });

  it('matches a color filter across the light/dark pair', () => {
    const { light, dark } = NOTE_COLOR_OPTIONS[1];
    const notes = [note({ id: '1', color: dark }), note({ id: '2', color: NOTE_COLOR_OPTIONS[2].light })];
    expect(filterNotes(notes, { filter: 'active', colorArgb: light }).map((n) => n.id)).toEqual(['1']);
  });

  it('filters by label name', () => {
    const notes = [note({ id: '1', labels: [labelFromName('Work')] }), note({ id: '2' })];
    expect(filterNotes(notes, { filter: 'active', labelName: 'Work' }).map((n) => n.id)).toEqual(['1']);
    expect(filterNotes(notes, { filter: 'active', labelName: 'Home' })).toEqual([]);
  });

  it('keeps pinned notes ahead of unpinned ones in every sort order', () => {
    const notes = [
      note({ id: 'new', timestamp: 300, position: 5 }),
      note({ id: 'pinned-old', timestamp: 100, position: 9, isPinned: true }),
    ];
    for (const sortOrder of ['newest', 'oldest', 'manual'] as const) {
      expect(filterNotes(notes, { filter: 'active', sortOrder })[0].id).toBe('pinned-old');
    }
  });

  it('sorts by timestamp for newest and oldest', () => {
    const notes = [
      note({ id: 'a', timestamp: 100 }),
      note({ id: 'c', timestamp: 300 }),
      note({ id: 'b', timestamp: 200 }),
    ];
    expect(filterNotes(notes, { filter: 'active', sortOrder: 'newest' }).map((n) => n.id)).toEqual([
      'c',
      'b',
      'a',
    ]);
    expect(filterNotes(notes, { filter: 'active', sortOrder: 'oldest' }).map((n) => n.id)).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('sorts manually by position, breaking ties with the newest timestamp', () => {
    const notes = [
      note({ id: 'second', position: 1, timestamp: 500 }),
      note({ id: 'tie-old', position: 0, timestamp: 100 }),
      note({ id: 'tie-new', position: 0, timestamp: 400 }),
    ];
    expect(filterNotes(notes, { filter: 'active', sortOrder: 'manual' }).map((n) => n.id)).toEqual([
      'tie-new',
      'tie-old',
      'second',
    ]);
  });

  it('does not mutate the input array', () => {
    const notes = [note({ id: 'b', timestamp: 100 }), note({ id: 'a', timestamp: 200 })];
    filterNotes(notes, { filter: 'active', sortOrder: 'newest' });
    expect(notes.map((n) => n.id)).toEqual(['b', 'a']);
  });

  it('ranks a text query by relevance rather than the chosen sort', () => {
    const inBody = note({ id: 'body', title: 'x', content: 'budget', timestamp: 900 });
    const inTitle = note({ id: 'title', title: 'budget', timestamp: 100 });
    expect(
      filterNotes([inBody, inTitle], {
        filter: 'active',
        searchQuery: 'budget',
        sortOrder: 'newest',
      }).map((n) => n.id),
    ).toEqual(['title', 'body']);
  });
});
