import { beforeEach, describe, expect, it } from 'vitest';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote, type Note, type NoteQueryFilters } from '@/types/note';

function note(id: string): Note {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}`, timestamp: 1 });
}

/**
 * The two failure channels are separate because they mean opposite things about whether the app
 * can still be used. A failed local read leaves nothing to show; a failed sync leaves everything
 * on the device exactly where it was.
 */
describe('notesStore error channels', () => {
  beforeEach(() => {
    useNotesStore.getState().reset();
  });

  it('keeps a sync error off the load status so the notes stay on screen', () => {
    useNotesStore.getState().setNotes([note('1'), note('2')]);
    useNotesStore.getState().setSyncError('Could not sync notes');

    const state = useNotesStore.getState();
    expect(state.status).toBe('ready');
    expect(state.error).toBeNull();
    expect(state.syncError).toBe('Could not sync notes');
    expect(state.notes).toHaveLength(2);
  });

  it('marks a local failure as an error status', () => {
    useNotesStore.getState().setError('IndexedDB is unavailable');

    expect(useNotesStore.getState().status).toBe('error');
    expect(useNotesStore.getState().error).toBe('IndexedDB is unavailable');
    expect(useNotesStore.getState().syncError).toBeNull();
  });

  it('clears each channel independently', () => {
    useNotesStore.getState().setError('local');
    useNotesStore.getState().setSyncError('cloud');

    useNotesStore.getState().clearError();
    expect(useNotesStore.getState().error).toBeNull();
    expect(useNotesStore.getState().status).toBe('ready');
    expect(useNotesStore.getState().syncError).toBe('cloud');

    useNotesStore.getState().clearSyncError();
    expect(useNotesStore.getState().syncError).toBeNull();
  });

  it('does not leave a stale local error after notes load successfully', () => {
    useNotesStore.getState().setError('local');
    useNotesStore.getState().setNotes([note('1')]);

    expect(useNotesStore.getState().error).toBeNull();
    expect(useNotesStore.getState().status).toBe('ready');
  });

  it('reset clears both channels', () => {
    useNotesStore.getState().setError('local');
    useNotesStore.getState().setSyncError('cloud');

    useNotesStore.getState().reset();

    expect(useNotesStore.getState().error).toBeNull();
    expect(useNotesStore.getState().syncError).toBeNull();
    expect(useNotesStore.getState().status).toBe('ready');
  });

  it('a local edit still clears a blocking error without clearing the sync banner', () => {
    useNotesStore.getState().setSyncError('cloud');
    useNotesStore.getState().setError('local');

    useNotesStore.getState().upsertLocalNote(note('9'));

    expect(useNotesStore.getState().error).toBeNull();
    expect(useNotesStore.getState().status).toBe('ready');
    // Saving locally says nothing about whether the cloud is reachable.
    expect(useNotesStore.getState().syncError).toBe('cloud');
  });
});

describe('notesStore filter persistence', () => {
  beforeEach(() => {
    useNotesStore.getState().reset();
  });

  it('keeps only where the user is and the sort, never a narrowing', () => {
    useNotesStore.getState().setFilters({
      filter: 'archived',
      sortOrder: 'oldest',
      searchQuery: 'milk',
      colors: [1],
      labelNames: ['Work'],
      flags: ['PINNED'],
      dateRange: { from: 0, to: 1 },
    });
    const partialize = useNotesStore.persist.getOptions().partialize!;
    expect(partialize(useNotesStore.getState())).toEqual({ filters: { filter: 'archived', sortOrder: 'oldest' } });
  });

  it('rehydrates an older stored shape over the defaults, so the sets are never missing', () => {
    const merge = useNotesStore.persist.getOptions().merge!;
    const legacy = { filters: { filter: 'trashed', sortOrder: 'newest', colorArgb: 5, labelName: 'Work' } };
    const merged = merge(legacy, useNotesStore.getState()) as { filters: NoteQueryFilters };
    expect(merged.filters).toMatchObject({
      filter: 'trashed',
      sortOrder: 'newest',
      colors: [],
      labelNames: [],
      flags: [],
      labelMatch: 'ANY',
      dateField: 'EDITED',
      dateRange: null,
    });
    expect(merged.filters).not.toHaveProperty('colorArgb');
  });

  it('a change only to set order still updates, so the sheet shows what was tapped', () => {
    useNotesStore.getState().setFilters({ labelNames: ['a', 'b'] });
    const before = useNotesStore.getState().filters;
    useNotesStore.getState().setFilters({ labelNames: ['a', 'b'] });
    expect(useNotesStore.getState().filters).toBe(before);
    useNotesStore.getState().setFilters({ labelNames: ['b', 'a'] });
    expect(useNotesStore.getState().filters.labelNames).toEqual(['b', 'a']);
  });
});
