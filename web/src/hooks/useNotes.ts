import { useMemo } from 'react';
import { notesContentKey } from '@/lib/notes/noteEquality';
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { useNotesStore } from '@/store/notesStore';
import type { NoteQueryFilters } from '@/types/note';
import { searchNotes } from '@/types/note';
import { collectUniqueLabels } from '@/types/label';
import { useAuthListener } from '@/hooks/useAuth';

/** Read notes state and actions. Does not subscribe to Firestore — use `useNotesSync` once in App. */
export function useNotes() {
  const { userId, isReady: authReady } = useAuthListener();
  const notes = useNotesStore((state) => state.notes);
  const status = useNotesStore((state) => state.status);
  const error = useNotesStore((state) => state.error);
  const filters = useNotesStore((state) => state.filters);

  const notesKey = notesContentKey(notes);
  const filterKey = `${filters.filter}|${filters.searchQuery ?? ''}|${filters.colorArgb ?? ''}|${filters.labelName ?? ''}|${filters.sortOrder ?? 'manual'}`;

  // notesKey/filterKey are content-derived guards: `notes` and `filters` get new references
  // on every store update even when nothing relevant to filtering changed.
  const { filteredNotes, isFuzzyResult } = useMemo(() => {
    const result = searchNotes(notes, filters);
    return { filteredNotes: result.notes, isFuzzyResult: result.isFuzzy };
  }, [notesKey, filterKey]);

  const labels = useMemo(() => collectUniqueLabels(notes), [notesKey]);

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
    filters,
    isLoading: status === 'loading',
    isEmpty: filteredNotes.length === 0,
    ...actions,
  };
}
