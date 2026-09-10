import { describe, expect, it } from 'vitest';
import { resolveNotesViewState } from '@/screens/main/notesViewState';

describe('resolveNotesViewState', () => {
  it('prefers loading over error and empty', () => {
    expect(
      resolveNotesViewState({
        isLoading: true,
        error: 'boom',
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'loading' });
  });

  it('shows a blocking error without empty when load failed and there are no notes', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: 'Could not sync notes',
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'blocking-error', message: 'Could not sync notes' });
  });

  it('keeps empty state when there is no error', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: null,
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({ kind: 'content', showErrorBanner: null, empty: true });
  });

  it('shows content with a soft banner when local notes exist despite a sync error', () => {
    expect(
      resolveNotesViewState({
        isLoading: false,
        error: 'Could not sync notes',
        notesCount: 3,
        filteredCount: 0,
      }),
    ).toEqual({
      kind: 'content',
      showErrorBanner: 'Could not sync notes',
      empty: true,
    });
  });
});
