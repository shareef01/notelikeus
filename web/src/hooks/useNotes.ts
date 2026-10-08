import { useMemo } from 'react';
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { useNotesStore } from '@/store/notesStore';
import type { NoteQueryFilters } from '@/types/note';
import { searchNotes } from '@/types/note';
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

  const { filteredNotes, isFuzzyResult } = useMemo(() => {
    const result = searchNotes(notes, filters);
    return { filteredNotes: result.notes, isFuzzyResult: result.isFuzzy };
  }, [notes, filters]);

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

  const actions = useMemo(
    () => ({
      setSearchQuery: (searchQuery: string) =>
        useNotesStore.getState().setFilters({ searchQuery }),
      setColorFilter: (colorArgb: number | null) =>
        useNotesStore.getState().setFilters({ colorArgb }),
      setLabelFilter: (labelName: string | null) =>
        useNotesStore.getState().setFilters({ labelName }),
      setNoteFilter: (filter: NoteQueryFilters['filter']) =>
        useNotesStore.getState().setFilters({ filter }),
      setSortOrder: (sortOrder: NonNullable<NoteQueryFilters['sortOrder']>) =>
        useNotesStore.getState().setFilters({ sortOrder }),
      clearFilters: () =>
        useNotesStore.getState().setFilters({ searchQuery: '', colorArgb: null, labelName: null }),
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
    isFuzzyResult,
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
