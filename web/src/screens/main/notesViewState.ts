export type NotesViewState =
  | { kind: 'loading' }
  | { kind: 'blocking-error'; message: string }
  | { kind: 'empty'; syncWarning: string | null }
  | { kind: 'content'; syncWarning: string | null };

/**
 * The single decision about what the notes area shows, in priority order:
 * loading → blocking local error → empty → content.
 *
 * One `kind` at a time, because the states are contradictory. "Nothing here yet — create your
 * first note" next to "could not load your notes" tells the user two different things about the
 * same screen, and the empty state's call to action is actively wrong when the reason the list is
 * empty is that reading it failed.
 *
 * Only a *local* failure blocks. A local failure means IndexedDB could not be read, so there is
 * genuinely nothing to show. Cloud trouble is different in kind: the notes are on the device and
 * fully editable, and the only thing that is wrong is that other devices have not heard about them
 * yet — so it rides along any of the other states as a warning rather than replacing them.
 *
 * A local failure with notes already in the store is the one blend that makes sense: those notes
 * were read successfully earlier, so they stay on screen with the failure shown above them.
 */
export function resolveNotesViewState(args: {
  isLoading: boolean;
  /** Blocking local-storage/bootstrap failure. */
  error: string | null;
  /** Non-blocking cloud sync failure. */
  syncError?: string | null;
  notesCount: number;
  filteredCount: number;
}): NotesViewState {
  if (args.isLoading) return { kind: 'loading' };

  if (args.error && args.notesCount === 0) {
    return { kind: 'blocking-error', message: args.error };
  }

  // A local error that did not block still has to be visible, and it is the more serious of the
  // two, so it wins the one banner slot.
  const syncWarning = args.error ?? args.syncError ?? null;

  if (args.filteredCount === 0) return { kind: 'empty', syncWarning };

  return { kind: 'content', syncWarning };
}
