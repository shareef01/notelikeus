import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ViewColumns = 1 | 2 | 3 | 4 | 5 | 6;

export type AuthMode = 'signin' | 'signup';

interface UiState {
  drawerOpen: boolean;
  sidebarCollapsed: boolean;
  viewColumns: ViewColumns;
  listScrolled: boolean;
  authScreen: AuthMode | null;
  labelsOpen: boolean;
  selectedNoteIds: string[];
  recentSearches: string[];
  setDrawerOpen: (open: boolean) => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  toggleSidebar: () => void;
  toggleDrawer: () => void;
  setViewColumns: (columns: ViewColumns) => void;
  cycleViewColumns: () => void;
  setListScrolled: (scrolled: boolean) => void;
  openAuthScreen: (mode: AuthMode) => void;
  closeAuthScreen: () => void;
  setLabelsOpen: (open: boolean) => void;
  toggleNoteSelection: (noteId: string) => void;
  clearSelection: () => void;
  toggleSelectAll: (noteIds: string[]) => void;
  addRecentSearch: (query: string) => void;
  clearRecentSearches: () => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      drawerOpen: false,
      sidebarCollapsed: false,
      viewColumns: 2,
      listScrolled: false,
      authScreen: null,
      labelsOpen: false,
      selectedNoteIds: [],
      recentSearches: [],
      setDrawerOpen: (drawerOpen) =>
        set((state) => (state.drawerOpen === drawerOpen ? state : { drawerOpen })),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      toggleSidebar: () => set({ sidebarCollapsed: !get().sidebarCollapsed }),
      toggleDrawer: () => set({ drawerOpen: !get().drawerOpen }),
      setViewColumns: (viewColumns) =>
        set((state) => (state.viewColumns === viewColumns ? state : { viewColumns })),
      cycleViewColumns: () => {
        const next: ViewColumns = get().viewColumns >= 4 ? 1 : ((get().viewColumns + 1) as ViewColumns);
        set({ viewColumns: next });
      },
      setListScrolled: (listScrolled) =>
        set((state) => (state.listScrolled === listScrolled ? state : { listScrolled })),
      openAuthScreen: (authScreen) => set({ authScreen, drawerOpen: false }),
      closeAuthScreen: () =>
        set((state) => (state.authScreen === null ? state : { authScreen: null })),
      setLabelsOpen: (labelsOpen) => set({ labelsOpen, drawerOpen: false }),
      toggleNoteSelection: (noteId) =>
        set((state) => {
          const selected = state.selectedNoteIds.includes(noteId)
            ? state.selectedNoteIds.filter((id) => id !== noteId)
            : [...state.selectedNoteIds, noteId];
          return { selectedNoteIds: selected };
        }),
      clearSelection: () =>
        set((state) => (state.selectedNoteIds.length === 0 ? state : { selectedNoteIds: [] })),
      toggleSelectAll: (noteIds) =>
        set((state) => {
          if (noteIds.length === 0) return state;
          const allSelected = noteIds.every((id) => state.selectedNoteIds.includes(id));
          if (allSelected) {
            const remaining = state.selectedNoteIds.filter((id) => !noteIds.includes(id));
            return { selectedNoteIds: remaining };
          }
          const merged = new Set([...state.selectedNoteIds, ...noteIds]);
          return { selectedNoteIds: Array.from(merged) };
        }),
      addRecentSearch: (query) =>
        set((state) => {
          if (!query.trim()) return state;
          const filtered = state.recentSearches.filter((s) => s !== query.trim());
          return { recentSearches: [query.trim(), ...filtered].slice(0, 10) };
        }),
      clearRecentSearches: () => set({ recentSearches: [] }),
    }),
    {
      name: 'notelikeus-ui',
      partialize: (state) => ({
        viewColumns: state.viewColumns,
        recentSearches: state.recentSearches
      }),
      skipHydration: true,
    },
  ),
);
