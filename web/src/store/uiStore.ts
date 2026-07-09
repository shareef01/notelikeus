import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ViewColumns = 1 | 2 | 3;

export type AuthMode = 'signin' | 'signup';

export type EditorRoute =
  | { mode: 'closed' }
  | { mode: 'new' }
  | { mode: 'edit'; noteId: string };

interface UiState {
  drawerOpen: boolean;
  viewColumns: ViewColumns;
  listScrolled: boolean;
  editorRoute: EditorRoute;
  authScreen: AuthMode | null;
  setDrawerOpen: (open: boolean) => void;
  toggleDrawer: () => void;
  setViewColumns: (columns: ViewColumns) => void;
  cycleViewColumns: () => void;
  setListScrolled: (scrolled: boolean) => void;
  openNewNote: () => void;
  openNote: (noteId: string) => void;
  closeEditor: () => void;
  openAuthScreen: (mode: AuthMode) => void;
  closeAuthScreen: () => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      drawerOpen: false,
      viewColumns: 2,
      listScrolled: false,
      editorRoute: { mode: 'closed' },
      authScreen: null,
      setDrawerOpen: (drawerOpen) =>
        set((state) => (state.drawerOpen === drawerOpen ? state : { drawerOpen })),
      toggleDrawer: () => set({ drawerOpen: !get().drawerOpen }),
      setViewColumns: (viewColumns) =>
        set((state) => (state.viewColumns === viewColumns ? state : { viewColumns })),
      cycleViewColumns: () => {
        const next: ViewColumns = get().viewColumns === 3 ? 1 : ((get().viewColumns + 1) as ViewColumns);
        set({ viewColumns: next });
      },
      setListScrolled: (listScrolled) =>
        set((state) => (state.listScrolled === listScrolled ? state : { listScrolled })),
      openNewNote: () => set({ editorRoute: { mode: 'new' }, drawerOpen: false }),
      openNote: (noteId) => set({ editorRoute: { mode: 'edit', noteId }, drawerOpen: false }),
      closeEditor: () => set({ editorRoute: { mode: 'closed' } }),
      openAuthScreen: (authScreen) => set({ authScreen, drawerOpen: false }),
      closeAuthScreen: () =>
        set((state) => (state.authScreen === null ? state : { authScreen: null })),
    }),
    {
      name: 'notelikeus-ui',
      partialize: (state) => ({ viewColumns: state.viewColumns }),
      skipHydration: true,
    },
  ),
);
