import { describe, expect, it } from 'vitest';
import { getEmptyState } from './mainEmptyState';

/**
 * The precedence between the branches is the behaviour worth pinning. It was inlined in
 * `MainScreen.tsx` and untested, so nothing stopped the order being rearranged — and the order is
 * what keeps "Create note" out of views where the new note would be immediately hidden.
 */
describe('getEmptyState', () => {
  it('reports the search when one is active, whatever the scope', () => {
    for (const filter of ['active', 'archived', 'trashed'] as const) {
      const state = getEmptyState(filter, false, true);
      expect(state.message).toBe('No matching notes');
      expect(state.showClearFilters).toBe(true);
    }
  });

  it('prefers the search message over the filter message', () => {
    // Both are active. Only one message can be shown, and the search box is the thing the user
    // just typed into.
    expect(getEmptyState('active', true, true).message).toBe('No matching notes');
  });

  it('reports filters when there is no search', () => {
    const state = getEmptyState('active', true, false);
    expect(state.message).toBe('No notes match your filters');
    expect(state.showClearFilters).toBe(true);
  });

  it('describes an empty archive and an empty trash differently', () => {
    expect(getEmptyState('archived', false, false)).toEqual({
      message: 'No archived notes',
      icon: 'archive',
    });

    const trash = getEmptyState('trashed', false, false);
    expect(trash.message).toBe('No notes in trash');
    expect(trash.icon).toBe('trash');
  });

  it('offers Create note only in the genuinely empty default view', () => {
    expect(getEmptyState('active', false, false).showCreate).toBe(true);

    // Anywhere else, a created note would either be hidden by the filter or belong to a scope it
    // cannot be created into.
    for (const state of [
      getEmptyState('active', true, false),
      getEmptyState('active', false, true),
      getEmptyState('archived', false, false),
      getEmptyState('trashed', false, false),
    ]) {
      expect(state.showCreate).toBeUndefined();
    }
  });

  it('never offers Clear filters when there is nothing to clear', () => {
    for (const filter of ['active', 'archived', 'trashed'] as const) {
      expect(getEmptyState(filter, false, false).showClearFilters).toBeUndefined();
    }
  });
});
