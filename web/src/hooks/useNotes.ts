import { useDeferredValue, useMemo } from 'react';
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { useNotesStore } from '@/store/notesStore';
import type { NoteQueryFilters } from '@/types/note';
import { searchNotes } from '@/types/note';
import { clearedFilters } from '@/lib/query/noteQuery';
import { collectUniqueLabels } from '@/types/label';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useAuthListener } from '@/hooks/useAuth';

/** Read notes state and actions. Does not subscribe to remote sync — use `useNotesSync` once in App. */
export function useNotes() {
  const { userId, isReady: authReady } = useAuthListener();
  const notes = useNotesStore((state) => state.notes);
  const status = useNotesStore((state) => state.status);
  const error = useNotesStore((state) => state.error);
  const syncError = useNotesStore((state) => state.syncError);
  const unreadableNoteIds = useNotesStore((state) => state.unreadableNoteIds);
  const filters = useNotesStore((state) => state.filters);

  // The list follows the filters a beat behind the search box (A13). Filtering and re-rendering a
  // large list on every keystroke blocked the keystroke itself from appearing: about 1 s per key
  // with 500 notes on a 4x-throttled CPU. Deferred, the field updates at once and React renders the
  // list in the background, dropping any render a newer keystroke has made stale.
  const listFilters = useDeferredValue(filters);

  const registeredLabels = useLabelRegistryStore((state) => state.labels);

  /**
   * Labels on notes, plus labels created but not yet used on any note.
   *
   * The registry exists for exactly that second case — its own comment says so — but nothing read it
   * here, so a label created in the manager appeared there and nowhere else: no chip in the filter
   * row, immediately or after a reload, and therefore no way to filter by it. Merged by name so a
   * label both a note and the registry know about still appears once.
   */
  const labels = useMemo(() => {
    const fromNotes = collectUniqueLabels(notes);
    const known = new Set(fromNotes.map((label) => label.name));
    const unused = Object.values(registeredLabels).filter((label) => !known.has(label.name));
    return unused.length ? [...fromNotes, ...unused] : fromNotes;
  }, [notes, registeredLabels]);

  // Every label the user has, used ones and unused ones, so `label:` resolves as it does on native.
  const labelNames = useMemo(() => labels.map((label) => label.name), [labels]);

  const { filteredNotes, isFuzzyResult, searchText, unknownOperators } = useMemo(() => {
    const result = searchNotes(notes, listFilters, { knownLabelNames: labelNames });
    return {
      filteredNotes: result.notes,
      isFuzzyResult: result.isFuzzy,
      searchText: result.searchText,
      unknownOperators: result.unknownOperators,
    };
  }, [notes, listFilters, labelNames]);

  const actions = useMemo(
    () => ({
      setSearchQuery: (searchQuery: string) =>
        useNotesStore.getState().setFilters({ searchQuery }),
      /** Merges a change into the filters; the Filters sheet and the filter row both go through it. */
      updateFilters: (patch: Partial<NoteQueryFilters>) => useNotesStore.getState().setFilters(patch),
      /** Shows one label's notes and nothing else, as tapping a label on a card does. */
      showLabel: (labelName: string) =>
        useNotesStore.getState().setFilters({ filter: 'active', labelNames: [labelName] }),
      setNoteFilter: (filter: NoteQueryFilters['filter']) =>
        useNotesStore.getState().setFilters({ filter }),
      setSortOrder: (sortOrder: NonNullable<NoteQueryFilters['sortOrder']>) =>
        useNotesStore.getState().setFilters({ sortOrder }),
      /** Kotlin `NoteQuery.cleared()`: keeps the scope, sort, date field and label match. */
      clearFilters: () => useNotesStore.getState().setFilters(clearedFilters()),
      saveNote,
      removeNote,
    }),
    [],
  );

  return {
    userId,
    authReady,
    notes,
    filteredNotes,
    /** The filters `filteredNotes` was computed from; trails `filters` while the user types. */
    listFilters,
    isFuzzyResult,
    /** The free text of the search once operators are taken out: what to highlight. */
    searchText,
    /** Operators the box holds that were not understood. */
    unknownOperators,
    labels,
    status,
    error,
    syncError,
    unreadableNoteIds,
    filters,
    isLoading: status === 'loading',
    isEmpty: filteredNotes.length === 0,
    ...actions,
  };
}
