import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Note, NoteQueryFilters } from '@/types/note';
import { notesContentEqual, notesEqual } from '@/lib/notes/noteEquality';

export type NotesLoadStatus = 'idle' | 'loading' | 'ready' | 'error';

interface NotesState {
  notes: Note[];
  status: NotesLoadStatus;
  /**
   * A failure of the *local* store — IndexedDB unavailable, the bootstrap read throwing.
   *
   * Blocking, because when it fires there is nothing to show and no edit that could be kept:
   * the durable local database is the app. Kept separate from {@link syncError} so a network
   * problem can never take the screen away from notes that are sitting on the device.
   */
  error: string | null;
  /**
   * A failure to reach or reconcile with the cloud — offline, realtime dropped, a refused
   * reconcile.
   *
   * Never blocking. Local IndexedDB data stays readable and editable throughout; the cloud
   * catching up later is what fixes it, and the next successful sync clears this.
   */
  syncError: string | null;
  filters: NoteQueryFilters;
  setNotes: (notes: Note[]) => void;
  upsertLocalNote: (note: Note) => void;
  removeLocalNote: (noteId: string) => void;
  setStatus: (status: NotesLoadStatus) => void;
  setError: (error: string) => void;
  clearError: () => void;
  setSyncError: (error: string) => void;
  clearSyncError: () => void;
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

/**
 * In-memory UI mirror of notes. Durable storage lives in IndexedDB (see notesLocalRepository.ts)
 * for guest and signed-in sessions; filters alone are persisted via localStorage.
 */
export const useNotesStore = create<NotesState>()(
  persist(
    (set, get) => ({
      notes: [],
      status: 'ready',
      error: null,
      syncError: null,
      filters: defaultFilters,
      setNotes: (incoming) => {
        const current = get().notes;
        if (notesContentEqual(current, incoming)) {
          if (get().status !== 'ready' || get().error != null) {
            set({ status: 'ready', error: null });
          }
          return;
        }
        set({ notes: incoming, status: 'ready', error: null });
      },
      upsertLocalNote: (note) => {
        const current = get().notes;
        const index = current.findIndex((entry) => entry.id === note.id);
        if (index >= 0 && notesEqual(current[index], note)) {
          return;
        }
        const notes = [...current];
        if (index >= 0) notes[index] = note;
        else notes.push(note);
        // Local durability succeeded — drop a stale sync banner so edits are not blocked by it.
        set({ notes, status: 'ready', error: null });
      },
      removeLocalNote: (noteId) => {
        const next = get().notes.filter((note) => note.id !== noteId);
        if (next.length === get().notes.length) return;
        set({ notes: next, status: 'ready', error: null });
      },
      setStatus: (status) =>
        set((state) => {
          if (status === 'loading') {
            if (state.status === 'loading' && state.error == null) return state;
            return { status, error: null };
          }
          return state.status === status ? state : { status };
        }),
      setError: (error) =>
        set((state) =>
          state.error === error && state.status === 'error' ? state : { error, status: 'error' },
        ),
      clearError: () =>
        set((state) => {
          if (state.error == null && state.status !== 'error') return state;
          return {
            error: null,
            status: state.status === 'error' ? 'ready' : state.status,
          };
        }),
      // Deliberately leaves `status` alone. Sync trouble is a banner over a working screen, not a
      // load state, so it must not turn a 'ready' store into an 'error' one and hide the notes.
      setSyncError: (syncError) =>
        set((state) => (state.syncError === syncError ? state : { syncError })),
      clearSyncError: () =>
        set((state) => (state.syncError == null ? state : { syncError: null })),
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
      reset: () =>
        set({
          notes: [],
          status: 'ready',
          error: null,
          syncError: null,
          filters: defaultFilters,
        }),
    }),
    {
      name: 'notelikeus-note-filters',
      skipHydration: true,
      partialize: (state) => ({ filters: state.filters }),
    },
  ),
);
