import { beforeEach, describe, expect, it } from 'vitest';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote, type Note } from '@/types/note';

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

/**
 * NL-08: a reload keeps which view is open and how it is sorted, and nothing else. The search
 * text, label and colour are about the user's own notes and localStorage is outside the sealing.
 */
describe('notesStore filter persistence', () => {
  const KEY = 'notelikeus-note-filters';

  beforeEach(() => {
    localStorage.removeItem(KEY);
    useNotesStore.getState().reset();
  });

  it('writes the view and sort but not the query, label or colour', () => {
    useNotesStore.getState().setFilters({
      filter: 'archived',
      sortOrder: 'oldest',
      searchQuery: 'tax letter',
      labelName: 'Finance',
      colorArgb: 0xfff28b82,
    });

    const stored = JSON.parse(localStorage.getItem(KEY)!);
    expect(stored.state.filters).toEqual({ filter: 'archived', sortOrder: 'oldest' });
    expect(JSON.stringify(stored)).not.toContain('tax letter');
    expect(JSON.stringify(stored)).not.toContain('Finance');
  });

  it('reopens on the persisted sort and view with no query, label or colour after a reload', async () => {
    useNotesStore.getState().setFilters({
      filter: 'archived',
      sortOrder: 'oldest',
      searchQuery: 'tax letter',
      labelName: 'Finance',
      colorArgb: 0xfff28b82,
    });
    const saved = localStorage.getItem(KEY)!;
    // What the next page load starts from: the in-memory defaults, then hydration from what the
    // last session saved. (setState writes through persist, so put the saved copy back after it.)
    useNotesStore.setState({ filters: { filter: 'active', sortOrder: 'newest' } });
    localStorage.setItem(KEY, saved);

    await useNotesStore.persist.rehydrate();

    expect(useNotesStore.getState().filters).toEqual({
      filter: 'archived',
      sortOrder: 'oldest',
      searchQuery: '',
      labelName: null,
      colorArgb: null,
    });
  });

  it('drops a query, label and colour left by an older build on the next load', async () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({
        state: {
          filters: {
            filter: 'trashed',
            sortOrder: 'oldest',
            searchQuery: 'old secret',
            labelName: 'Health',
            colorArgb: 0xffaecbfa,
          },
        },
        version: 0,
      }),
    );

    await useNotesStore.persist.rehydrate();

    expect(useNotesStore.getState().filters).toEqual({
      filter: 'trashed',
      sortOrder: 'oldest',
      searchQuery: '',
      labelName: null,
      colorArgb: null,
    });
    const rewritten = JSON.parse(localStorage.getItem(KEY)!);
    expect(rewritten.version).toBe(1);
    expect(JSON.stringify(rewritten)).not.toContain('old secret');
  });
});
