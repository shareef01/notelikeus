import { useMemo } from 'react';
import { uploadAllNotes } from '@/lib/firestore/notesRepository';
import { notesContentKey } from '@/lib/notes/noteEquality';
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { useNotesStore } from '@/store/notesStore';
import type { NoteQueryFilters } from '@/types/note';
import { filterNotes } from '@/types/note';
import type { Label } from '@/types/label';
import { useAuthListener } from '@/hooks/useAuth';

function collectLabels(notes: ReturnType<typeof useNotesStore.getState>['notes']): Label[] {
  const map = new Map<string, Label>();
  for (const note of notes) {
    for (const label of note.labels) {
      map.set(label.name.toLowerCase(), label);
    }
  }
  return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
}

/** Read notes state and actions. Does not subscribe to Firestore — use `useNotesSync` once in App. */
export function useNotes() {
  const { userId, isReady: authReady } = useAuthListener();
  const notes = useNotesStore((state) => state.notes);
  const status = useNotesStore((state) => state.status);
  const error = useNotesStore((state) => state.error);
  const filters = useNotesStore((state) => state.filters);

  const notesKey = notesContentKey(notes);
  const filterKey = `${filters.filter}|${filters.searchQuery ?? ''}|${filters.colorArgb ?? ''}|${filters.labelName ?? ''}|${filters.sortOrder ?? 'manual'}`;

  const filteredNotes = useMemo(
    () => filterNotes(notes, filters),
    // notesKey/filterKey are content-derived guards: `notes` and `filters` get new references
    // on every store update even when nothing relevant to filtering changed, so they're
    // deliberately left out of the deps to avoid recomputing on every render.
    [notesKey, filterKey],
  );

  const labels = useMemo(() => collectLabels(notes), [notesKey]);

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
      clearFilters: () => {
        useNotesStore.getState().setFilters({ searchQuery: '', colorArgb: null, labelName: null });
        useNotesStore.getState().setError(null);
      },
      saveNote,
      removeNote,
      syncAll: async () => {
        if (!userId) return 0;
        return uploadAllNotes(userId, useNotesStore.getState().notes);
      },
    }),
    [userId],
  );

  return {
    userId,
    authReady,
    notes,
    filteredNotes,
    labels,
    status,
    error,
    filters,
    isLoading: status === 'loading',
    isEmpty: filteredNotes.length === 0,
    ...actions,
  };
}
