import { create } from 'zustand';
import type { Note, NoteQueryFilters } from '@/types/note';
import { notesContentEqual, notesEqual } from '@/lib/notes/noteEquality';
export type NotesLoadStatus = 'idle' | 'loading' | 'ready' | 'error';

interface NotesState {
  notes: Note[];
  status: NotesLoadStatus;
  error: string | null;
  filters: NoteQueryFilters;
  setNotes: (notes: Note[]) => void;
  upsertLocalNote: (note: Note) => void;
  removeLocalNote: (noteId: string) => void;
  setStatus: (status: NotesLoadStatus) => void;
  setError: (error: string | null) => void;
  setFilters: (patch: Partial<NoteQueryFilters>) => void;
  reset: () => void;
}

const defaultFilters: NoteQueryFilters = {
  filter: 'active',
  searchQuery: '',
  colorArgb: null,
  labelName: null,
  sortOrder: 'manual',
};

export const useNotesStore = create<NotesState>()((set, get) => ({
  notes: [],
  status: 'loading',
  error: null,
  filters: defaultFilters,
  setNotes: (incoming) => {
    const current = get().notes;
    const deduped = Array.from(
      new Map(incoming.map((note) => [note.cloudId || note.id, note])).values(),
    );
    if (notesContentEqual(current, deduped)) {
      if (get().status !== 'ready' || get().error != null) {
        set({ status: 'ready', error: null });
      }
      return;
    }
    set({ notes: deduped, status: 'ready', error: null });
  },
  upsertLocalNote: (note) => {
    const current = get().notes;
    let index = current.findIndex((entry) => entry.id === note.id);
    if (index < 0 && note.cloudId) {
      index = current.findIndex((entry) => entry.cloudId === note.cloudId);
    }
    if (index >= 0 && notesEqual(current[index], note)) {
      return;
    }
    const notes = [...current];
    if (index >= 0) notes[index] = note;
    else notes.push(note);
    set({ notes, status: 'ready' });
  },
  removeLocalNote: (noteId) => {
    const next = get().notes.filter(
      (note) => note.id !== noteId && note.cloudId !== noteId,
    );
    if (next.length === get().notes.length) return;
    set({ notes: next, status: 'ready' });
  },
  setStatus: (status) =>
    set((state) => (state.status === status ? state : { status })),
  setError: (error) =>
    set((state) =>
      state.error === error && state.status === 'error' ? state : { error, status: 'error' },
    ),
  setFilters: (patch) => {
    const next = { ...get().filters, ...patch };
    const current = get().filters;
    const unchanged =
      current.filter === next.filter &&
      (current.searchQuery ?? '') === (next.searchQuery ?? '') &&
      current.colorArgb === next.colorArgb &&
      current.labelName === next.labelName &&
      current.sortOrder === next.sortOrder;
    if (unchanged) return;
    set({ filters: next });
  },
  reset: () => set({ notes: [], status: 'loading', error: null, filters: defaultFilters }),
}));
