import { beforeEach, describe, expect, it } from 'vitest';
import { useUiStore, type ViewColumns } from '@/store/uiStore';

const initial = useUiStore.getState();

beforeEach(() => {
  useUiStore.setState(initial, true);
});

describe('drawer and sidebar', () => {
  it('toggles the drawer', () => {
    useUiStore.getState().toggleDrawer();
    expect(useUiStore.getState().drawerOpen).toBe(true);
    useUiStore.getState().toggleDrawer();
    expect(useUiStore.getState().drawerOpen).toBe(false);
  });

  it('keeps the same state object when setting an unchanged value', () => {
    const before = useUiStore.getState();
    useUiStore.getState().setDrawerOpen(false);
    expect(useUiStore.getState()).toBe(before);
  });

  it('toggles the collapsed sidebar', () => {
    useUiStore.getState().toggleSidebarCollapsed();
    expect(useUiStore.getState().sidebarCollapsed).toBe(true);
  });
});

describe('view columns', () => {
  it('cycles 2 → 3 → 1 → 2', () => {
    const seen: ViewColumns[] = [];
    for (let i = 0; i < 3; i += 1) {
      useUiStore.getState().cycleViewColumns();
      seen.push(useUiStore.getState().viewColumns);
    }
    expect(seen).toEqual([3, 1, 2]);
  });

  it('sets a specific column count', () => {
    useUiStore.getState().setViewColumns(3);
    expect(useUiStore.getState().viewColumns).toBe(3);
  });
});

describe('editor routing', () => {
  it('opens a new note and closes the drawer', () => {
    useUiStore.getState().setDrawerOpen(true);
    useUiStore.getState().openNewNote();
    expect(useUiStore.getState().editorRoute).toEqual({ mode: 'new' });
    expect(useUiStore.getState().drawerOpen).toBe(false);
  });

  it('opens and closes an existing note', () => {
    useUiStore.getState().openNote('n1');
    expect(useUiStore.getState().editorRoute).toEqual({ mode: 'edit', noteId: 'n1' });
    useUiStore.getState().closeEditor();
    expect(useUiStore.getState().editorRoute).toEqual({ mode: 'closed' });
  });

  it('opens the auth screen and the labels sheet with the drawer closed', () => {
    useUiStore.getState().setDrawerOpen(true);
    useUiStore.getState().openAuthScreen('signup');
    expect(useUiStore.getState()).toMatchObject({ authScreen: 'signup', drawerOpen: false });

    useUiStore.getState().closeAuthScreen();
    expect(useUiStore.getState().authScreen).toBeNull();

    useUiStore.getState().setDrawerOpen(true);
    useUiStore.getState().setLabelsOpen(true);
    expect(useUiStore.getState()).toMatchObject({ labelsOpen: true, drawerOpen: false });
  });
});

describe('selection', () => {
  it('toggles individual notes in and out of the selection', () => {
    const { toggleNoteSelection } = useUiStore.getState();
    toggleNoteSelection('a');
    toggleNoteSelection('b');
    expect(useUiStore.getState().selectedNoteIds).toEqual(['a', 'b']);
    toggleNoteSelection('a');
    expect(useUiStore.getState().selectedNoteIds).toEqual(['b']);
  });

  it('selects all visible notes, then clears them on a second toggle', () => {
    useUiStore.getState().toggleSelectAll(['a', 'b']);
    expect(useUiStore.getState().selectedNoteIds).toEqual(['a', 'b']);
    useUiStore.getState().toggleSelectAll(['a', 'b']);
    expect(useUiStore.getState().selectedNoteIds).toEqual([]);
  });

  it('merges without duplicating and only clears the visible ids', () => {
    useUiStore.getState().toggleNoteSelection('a');
    useUiStore.getState().toggleSelectAll(['a', 'b']);
    expect(useUiStore.getState().selectedNoteIds).toEqual(['a', 'b']);

    useUiStore.getState().toggleSelectAll(['b']);
    expect(useUiStore.getState().selectedNoteIds).toEqual(['a']);
  });

  it('ignores an empty select-all and clears the whole selection', () => {
    useUiStore.getState().toggleNoteSelection('a');
    const before = useUiStore.getState();
    useUiStore.getState().toggleSelectAll([]);
    expect(useUiStore.getState()).toBe(before);

    useUiStore.getState().clearSelection();
    expect(useUiStore.getState().selectedNoteIds).toEqual([]);
  });
});

describe('recent searches', () => {
  it('stores trimmed queries most-recent-first without duplicates', () => {
    const { addRecentSearch } = useUiStore.getState();
    addRecentSearch('milk');
    addRecentSearch('bread');
    addRecentSearch('  milk  ');
    expect(useUiStore.getState().recentSearches).toEqual(['milk', 'bread']);
  });

  it('ignores blank queries', () => {
    const before = useUiStore.getState();
    useUiStore.getState().addRecentSearch('   ');
    expect(useUiStore.getState()).toBe(before);
  });

  it('caps the history at 10 entries', () => {
    for (let i = 0; i < 12; i += 1) useUiStore.getState().addRecentSearch(`q${i}`);
    const { recentSearches } = useUiStore.getState();
    expect(recentSearches).toHaveLength(10);
    expect(recentSearches[0]).toBe('q11');
  });

  it('clears the history', () => {
    useUiStore.getState().addRecentSearch('milk');
    useUiStore.getState().clearRecentSearches();
    expect(useUiStore.getState().recentSearches).toEqual([]);
  });
});

describe('persistence', () => {
  it('persists only layout preferences, never transient UI state', () => {
    const partialize = useUiStore.persist.getOptions().partialize;
    expect(partialize).toBeDefined();
    expect([...Object.keys(partialize!(useUiStore.getState()) as object)].sort()).toEqual([
      'editorLayout',
      'recentSearches',
      'sidebarCollapsed',
      'viewColumns',
    ]);
  });
});
