import { describe, expect, it } from 'vitest';
import { resolveNotesViewState } from '@/screens/main/notesViewState';

describe('resolveNotesViewState', () => {
  it('prefers loading over error and empty', () => {
    expect(
      resolveNotesViewState({
        isLoading: true,
        error: 'boom',
        syncError: 'also boom',
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'loading' });
  });

  it('shows a blocking error without empty when the local load failed and there are no notes', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: 'Could not load notes stored on this device.',
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({
      kind: 'blocking-error',
      message: 'Could not load notes stored on this device.',
    });
  });

  it('keeps empty state when there is no error at all', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: null,
        syncError: null,
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'empty', syncWarning: null });
  });

  /**
   * The distinction the whole split exists for: an unreachable cloud must never take the screen
   * away from notes that are sitting in IndexedDB — nor from a genuinely empty library, which is
   * a normal thing for a new account to have while sync is failing.
   */
  it('never blocks on a sync error, with or without notes', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: null,
        syncError: 'Could not sync notes',
        notesCount: 3,
        filteredCount: 3,
      }),
    ).toEqual({ kind: 'content', syncWarning: 'Could not sync notes' });

    expect(
      resolveNotesViewState({
        isLoading: false,
        error: null,
        syncError: 'Could not sync notes',
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'empty', syncWarning: 'Could not sync notes' });
  });

  it('degrades a local error to a banner when notes are already on screen', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: 'Could not load notes',
        syncError: 'Could not sync notes',
        notesCount: 3,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'empty', syncWarning: 'Could not load notes' });
  });

  it('is mutually exclusive: a blocking error is never also the empty state', () => {
    const states = [
      { isLoading: true, error: 'x', syncError: 'y', notesCount: 0, filteredCount: 0 },
      { isLoading: false, error: 'x', syncError: 'y', notesCount: 0, filteredCount: 0 },
      { isLoading: false, error: null, syncError: 'y', notesCount: 0, filteredCount: 0 },
      { isLoading: false, error: null, syncError: null, notesCount: 2, filteredCount: 2 },
    ];
    const kinds = states.map((args) => resolveNotesViewState(args).kind);

    expect(kinds).toEqual(['loading', 'blocking-error', 'empty', 'content']);
  });

  it('treats a missing syncError argument as no warning', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: null,
        notesCount: 1,
        filteredCount: 1,
      }),
    ).toEqual({ kind: 'content', syncWarning: null });
  });
});
