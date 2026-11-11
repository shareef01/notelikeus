import { describe, expect, it } from 'vitest';
import { createEmptyNote, searchNotes, type Note, type NoteQueryFilters } from '@/types/note';
import { labelFromName } from '@/types/label';
import { NOTE_COLOR_OPTIONS } from '@/theme/colors';
import {
  OPEN_END,
  clearedFilters,
  datePresetOf,
  datePresetRange,
  hasActiveFilters,
  narrowingEqual,
  sheetFilterCount,
  singleFilterColor,
  toggleColor,
} from '@/lib/query/noteQuery';

/** Noon on 2026-11-11, local time, so day arithmetic never straddles midnight. */
const NOW = new Date(2026, 10, 11, 12, 0, 0).getTime();
const DAY = 24 * 60 * 60 * 1000;

let nextId = 1;
function note(title: string, partial: Partial<Note> = {}): Note {
  const id = nextId++;
  return createEmptyNote({ id: String(id), localId: id, title, timestamp: NOW, color: 0, ...partial });
}

function titles(notes: Note[], filters: Partial<NoteQueryFilters>): string[] {
  return searchNotes(notes, { filter: 'active', sortOrder: 'oldest', ...filters }, { now: NOW }).notes.map(
    (n) => n.title,
  ).sort();
}

const coral = NOTE_COLOR_OPTIONS[1]!;
const green = NOTE_COLOR_OPTIONS[4]!;
const blue = NOTE_COLOR_OPTIONS[6]!;

describe('Filters sheet combinations match native NoteQueryMatcher', () => {
  const notes = [
    note('coral work', { color: coral.light, labels: [labelFromName('Work')], timestamp: NOW - 3 * DAY }),
    note('green both', {
      color: green.dark,
      labels: [labelFromName('Work'), labelFromName('Urgent')],
      isPinned: true,
      timestamp: NOW - 10 * DAY,
    }),
    note('blue urgent', { color: blue.light, labels: [labelFromName('Urgent')], timestamp: NOW - 40 * DAY }),
    note('plain', { checklist: [{ id: 'c', text: 'x', isChecked: false, position: 0 } as never] }),
    note('archived green', { color: green.light, isArchived: true }),
  ];

  it('several colours match any of them, across the light and dark variants', () => {
    const colors = toggleColor(toggleColor([], coral.light), green.light);
    expect(titles(notes, { colors })).toEqual(['coral work', 'green both']);
  });

  it('labels match any by default and all when asked', () => {
    const labelNames = ['Work', 'Urgent'];
    expect(titles(notes, { labelNames })).toEqual(['blue urgent', 'coral work', 'green both']);
    expect(titles(notes, { labelNames, labelMatch: 'ALL' })).toEqual(['green both']);
  });

  it('flags all have to hold', () => {
    expect(titles(notes, { flags: ['PINNED'] })).toEqual(['green both']);
    expect(titles(notes, { flags: ['UNLABELED', 'HAS_UNCHECKED_ITEMS'] })).toEqual(['plain']);
    expect(titles(notes, { flags: ['UNLABELED', 'PINNED'] })).toEqual([]);
  });

  it('dimensions combine with AND', () => {
    expect(
      titles(notes, { colors: toggleColor([], green.light), labelNames: ['Urgent'], flags: ['PINNED'] }),
    ).toEqual(['green both']);
    expect(titles(notes, { colors: toggleColor([], coral.light), labelNames: ['Urgent'] })).toEqual([]);
  });

  it('scope picks the pile before anything else narrows', () => {
    expect(titles(notes, { filter: 'archived', colors: toggleColor([], green.dark) })).toEqual(['archived green']);
  });

  it('typed operators add to the sheet’s labels, and any/all applies to the union', () => {
    expect(titles(notes, { searchQuery: 'label:urgent', labelNames: ['Work'] })).toEqual(['blue urgent', 'coral work', 'green both']);
    expect(titles(notes, { searchQuery: 'label:urgent', labelNames: ['Work'], labelMatch: 'ALL' })).toEqual(['green both']);
  });
});

describe('date ranges', () => {
  const notes = [
    note('today', { timestamp: NOW }),
    note('six days ago', { timestamp: NOW - 6 * DAY }),
    note('twenty days ago', { timestamp: NOW - 20 * DAY }),
    note('ninety days ago', { timestamp: NOW - 90 * DAY }),
  ];

  it('presets start at local midnight some days back and stay open forward', () => {
    const today = datePresetRange('TODAY', NOW)!;
    expect(today.from).toBe(new Date(2026, 10, 11).getTime());
    expect(today.to).toBe(OPEN_END);
    expect(datePresetRange('LAST_7', NOW)!.from).toBe(new Date(2026, 10, 4).getTime());
    expect(datePresetRange('ANY', NOW)).toBeNull();
  });

  it('each preset narrows to its window', () => {
    const range = (preset: 'TODAY' | 'LAST_7' | 'LAST_30') => datePresetRange(preset, NOW);
    expect(titles(notes, { dateRange: range('TODAY') })).toEqual(['today']);
    expect(titles(notes, { dateRange: range('LAST_7') })).toEqual(['six days ago', 'today']);
    expect(titles(notes, { dateRange: range('LAST_30') })).toEqual(['six days ago', 'today', 'twenty days ago']);
  });

  it('a range includes its start and excludes its end', () => {
    expect(titles(notes, { dateRange: { from: NOW - 6 * DAY, to: NOW } })).toEqual(['six days ago']);
  });

  it('the reminder field reads the reminder, and a note without one never matches', () => {
    const withReminders = [
      note('soon', { timestamp: NOW - 90 * DAY, reminderTimestamp: NOW + DAY }),
      note('none', { timestamp: NOW }),
    ];
    const window = { from: NOW, to: NOW + 2 * DAY };
    expect(titles(withReminders, { dateField: 'REMINDER', dateRange: window })).toEqual(['soon']);
    expect(titles(withReminders, { dateField: 'EDITED', dateRange: window })).toEqual(['none']);
  });

  it('a typed after: replaces the sheet’s window, as native does', () => {
    expect(titles(notes, { searchQuery: 'after:2026-08-01', dateRange: datePresetRange('TODAY', NOW) })).toEqual(['ninety days ago', 'six days ago', 'today', 'twenty days ago']);
  });

  it('recognises which preset is on, and none for a custom window', () => {
    expect(datePresetOf(null, NOW)).toBe('ANY');
    expect(datePresetOf(datePresetRange('LAST_30', NOW), NOW)).toBe('LAST_30');
    expect(datePresetOf({ from: 1, to: 2 }, NOW)).toBeNull();
  });
});

describe('NoteQuery helpers', () => {
  it('hasActiveFilters ignores scope, sort, date field and label match', () => {
    expect(hasActiveFilters({ filter: 'trashed', dateField: 'REMINDER', labelMatch: 'ALL' })).toBe(false);
    expect(hasActiveFilters({ filter: 'active', searchQuery: '   ' })).toBe(false);
    expect(hasActiveFilters({ filter: 'active', flags: ['PINNED'] })).toBe(true);
    expect(hasActiveFilters({ filter: 'active', dateRange: { from: 0, to: 1 } })).toBe(true);
  });

  it('cleared() drops every narrowing dimension and keeps the rest', () => {
    const before: NoteQueryFilters = {
      filter: 'archived',
      sortOrder: 'oldest',
      searchQuery: 'milk',
      colors: [coral.light],
      labelNames: ['Work'],
      labelMatch: 'ALL',
      flags: ['PINNED'],
      dateField: 'REMINDER',
      dateRange: { from: 0, to: 1 },
    };
    const after = { ...before, ...clearedFilters() };
    expect(hasActiveFilters(after)).toBe(false);
    expect(after).toMatchObject({ filter: 'archived', sortOrder: 'oldest', labelMatch: 'ALL', dateField: 'REMINDER' });
  });

  it('toggling a colour carries its counterpart both ways', () => {
    const on = toggleColor([], coral.dark);
    expect(on.sort()).toEqual([coral.light, coral.dark].sort());
    expect(toggleColor(on, coral.light)).toEqual([]);
  });

  it('compares narrowings as sets, ignoring sort', () => {
    expect(
      narrowingEqual(
        { filter: 'active', labelNames: ['a', 'b'], flags: ['PINNED', 'UNTITLED'] },
        { filter: 'active', labelNames: ['b', 'a'], flags: ['UNTITLED', 'PINNED'] },
      ),
    ).toBe(true);
    expect(narrowingEqual({ filter: 'active' }, { filter: 'archived' })).toBe(false);
  });

  it('counts the sheet’s filters, one per hue', () => {
    expect(
      sheetFilterCount({
        filter: 'active',
        colors: toggleColor(toggleColor([], coral.light), green.light),
        labelNames: ['Work'],
        flags: ['PINNED'],
        dateRange: { from: 0, to: 1 },
      }),
    ).toBe(5);
  });

  it('a new note takes the filter colour only when one hue is chosen', () => {
    expect(singleFilterColor(toggleColor([], coral.light))).toBe(coral.light);
    expect(singleFilterColor(toggleColor(toggleColor([], coral.light), blue.light))).toBeNull();
    expect(singleFilterColor([])).toBeNull();
  });
});
