export type NotesViewState =
  | { kind: 'loading' }
  | { kind: 'blocking-error'; message: string }
  | { kind: 'content'; showErrorBanner: string | null; empty: boolean };

/**
 * loading → blocking error (no local notes) → empty/content.
 * Never pairs a failed load with an empty-state message when the store has no notes.
 */
export function resolveNotesViewState(args: {
  isLoading: boolean;
  error: string | null;
  notesCount: number;
  filteredCount: number;
}): NotesViewState {
  if (args.isLoading) return { kind: 'loading' };

  if (args.error && args.notesCount === 0) {
    return { kind: 'blocking-error', message: args.error };
  }

  return {
    kind: 'content',
    showErrorBanner: args.error,
    empty: args.filteredCount === 0,
  };
}
