import { beforeEach, describe, expect, it } from 'vitest';
import {
  SAVED_FILTER_MAX,
  SAVED_FILTER_MAX_NAME_LENGTH,
  findSavedFilter,
  sanitizeSavedFilters,
  withSavedFilter,
  withoutSavedFilter,
} from '@/lib/query/savedFilters';
import { OPEN_END, narrowingEqual } from '@/lib/query/noteQuery';
import { useUiStore } from '@/store/uiStore';
import { useNotesStore } from '@/store/notesStore';
import { searchNotes, createEmptyNote, type NoteQueryFilters } from '@/types/note';
import { labelFromName } from '@/types/label';

const invoices: NoteQueryFilters = {
  filter: 'active',
  sortOrder: 'oldest',
  searchQuery: ' invoice ',
  labelNames: ['Work', 'Money'],
  labelMatch: 'ALL',
  flags: ['HAS_LINKS'],
  dateField: 'CREATED',
  dateRange: { from: 1_700_000_000_000, to: OPEN_END },
};

describe('saving a filter', () => {
  it('stores the narrowing only, never the sort', () => {
    const [saved] = withSavedFilter([], 'Invoices', invoices);
    expect(saved!.name).toBe('Invoices');
    expect(saved!.query).not.toHaveProperty('sortOrder');
    expect(saved!.query.searchQuery).toBe('invoice');
    expect(narrowingEqual(saved!.query, invoices)).toBe(true);
  });

  it('puts the newest first and replaces a name, ignoring case', () => {
    let list = withSavedFilter([], 'Invoices', invoices);
    list = withSavedFilter(list, 'Green', { filter: 'active', colors: [1] });
    list = withSavedFilter(list, 'INVOICES', { filter: 'archived' });
    expect(list.map((f) => f.name)).toEqual(['INVOICES', 'Green']);
    expect(list[0]!.query.filter).toBe('archived');
    expect(findSavedFilter(list, ' invoices ')?.name).toBe('INVOICES');
  });

  it('keeps the newest twenty, so a full list replaces the oldest', () => {
    let list = withSavedFilter([], 'oldest', { filter: 'active', flags: ['PINNED'] });
    for (let i = 1; i < SAVED_FILTER_MAX; i++) list = withSavedFilter(list, `f${i}`, { filter: 'active' });
    expect(list).toHaveLength(SAVED_FILTER_MAX);
    list = withSavedFilter(list, 'newest', { filter: 'active' });
    expect(list).toHaveLength(SAVED_FILTER_MAX);
    expect(list[0]!.name).toBe('newest');
    expect(list.some((f) => f.name === 'oldest')).toBe(false);
  });

  it('trims and caps the name, and refuses an empty one', () => {
    const long = 'x'.repeat(SAVED_FILTER_MAX_NAME_LENGTH + 10);
    expect(withSavedFilter([], `  ${long}`, invoices)[0]!.name).toHaveLength(SAVED_FILTER_MAX_NAME_LENGTH);
    expect(withSavedFilter([], '   ', invoices)).toEqual([]);
  });

  it('deletes by the exact name the row shows', () => {
    const list = withSavedFilter(withSavedFilter([], 'A', invoices), 'B', invoices);
    expect(withoutSavedFilter(list, 'A').map((f) => f.name)).toEqual(['B']);
  });

  it('survives a JSON round trip, open-ended date window included', () => {
    const list = withSavedFilter([], 'Invoices', invoices);
    const restored = sanitizeSavedFilters(JSON.parse(JSON.stringify(list)));
    expect(restored).toEqual(list);
    expect(restored[0]!.query.dateRange!.to).toBe(OPEN_END);
  });

  it('drops anything in storage that is not a saved filter', () => {
    const restored = sanitizeSavedFilters([
      null,
      { name: 'ok', query: { filter: 'active', flags: ['PINNED', 'NOPE'], colors: [1, 'x'] } },
      { name: 'OK', query: { filter: 'active' } },
      { name: '', query: { filter: 'active' } },
      { name: 'bad scope', query: { filter: 'everywhere' } },
      { name: 'bad range', query: { filter: 'active', dateRange: { from: null, to: 5 } } },
    ]);
    expect(restored.map((f) => f.name)).toEqual(['ok', 'bad range']);
    expect(restored[0]!.query.flags).toEqual(['PINNED']);
    expect(restored[0]!.query.colors).toEqual([1]);
    expect(restored[1]!.query.dateRange).toBeNull();
    expect(sanitizeSavedFilters('nope')).toEqual([]);
  });
});

describe('saved filters in the UI preferences store', () => {
  beforeEach(() => {
    useUiStore.getState().clearSavedFilters();
    useNotesStore.getState().reset();
  });

  it('applying one restores the notes it names and leaves the sort alone', () => {
    const notes = [
      createEmptyNote({ id: '1', localId: 1, title: 'invoice', labels: [labelFromName('Work'), labelFromName('Money')], content: 'https://pay' }),
      createEmptyNote({ id: '2', localId: 2, title: 'invoice', labels: [labelFromName('Work')], content: 'https://pay' }),
    ];
    const at = Date.now();
    useUiStore.getState().saveFilter('Invoices', { ...invoices, dateRange: { from: at - 1000, to: OPEN_END } });
    useNotesStore.getState().setFilters({ sortOrder: 'newest' });

    const [saved] = useUiStore.getState().savedFilters;
    useNotesStore.getState().setFilters({ ...saved!.query });

    const filters = useNotesStore.getState().filters;
    expect(filters.sortOrder).toBe('newest');
    expect(narrowingEqual(filters, saved!.query)).toBe(true);
    expect(searchNotes(notes, filters).notes.map((n) => n.id)).toEqual(['1']);
  });

  it('deletes and clears', () => {
    useUiStore.getState().saveFilter('A', invoices);
    useUiStore.getState().saveFilter('B', invoices);
    useUiStore.getState().deleteSavedFilter('A');
    expect(useUiStore.getState().savedFilters.map((f) => f.name)).toEqual(['B']);
    useUiStore.getState().clearSavedFilters();
    expect(useUiStore.getState().savedFilters).toEqual([]);
  });
});
