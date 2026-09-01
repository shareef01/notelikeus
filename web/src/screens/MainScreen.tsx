import { AddIcon } from '@/components/icons/Icons';

import { ToastHost } from '@/components/feedback/ToastHost';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import { InstallPrompt } from '@/components/layout/InstallPrompt';
import { OfflineBanner } from '@/components/layout/OfflineBanner';
import { SideDrawer } from '@/components/layout/SideDrawer';
import { TopBar } from '@/components/layout/TopBar';
import { NoteStaggeredGrid } from '@/components/notes/NoteStaggeredGrid';
import { NotesEmptyState } from '@/components/notes/NotesEmptyState';
import { NotesLoadingGrid } from '@/components/notes/NotesLoadingGrid';
import { SearchNotice } from '@/components/notes/SearchNotice';
import { TrashBanner } from '@/components/notes/TrashBanner';




import { useAuthListener } from '@/hooks/useAuth';

import { useCloudSync } from '@/hooks/useCloudSync';

import { useNotes } from '@/hooks/useNotes';






import { useSettingsStore } from '@/store/settingsStore';

import { useToastStore } from '@/store/toastStore';

import { useUiStore } from '@/store/uiStore';

import type { Note, NoteFilter } from '@/types/note';
import { MainDialogs, NO_DIALOGS_OPEN, type MainDialogState } from '@/screens/main/MainDialogs';
import { getEmptyState } from '@/screens/main/mainEmptyState';
import { useAccountActions } from '@/screens/main/useAccountActions';
import { useNoteActions } from '@/screens/main/useNoteActions';

import { useIsTabletUp } from '@/hooks/useMediaQuery';
import { useShortcuts } from '@/hooks/useShortcuts';
import { lazy, Suspense, useCallback, useMemo, useRef, useState } from 'react';

const EditorScreen = lazy(() =>
  import('@/screens/EditorScreen').then((module) => ({ default: module.EditorScreen })),
);



const SORT_ORDERS = ['manual', 'newest', 'oldest'] as const;

const SORT_ORDER_LABELS: Record<(typeof SORT_ORDERS)[number], string> = {
  manual: 'Manual order',
  newest: 'Newest first',
  oldest: 'Oldest first',
};



export function MainScreen() {

  const scrollRef = useRef<HTMLDivElement>(null);

  const searchInputRef = useRef<HTMLInputElement>(null);

  const backupInputRef = useRef<HTMLInputElement>(null);

  const { user } = useAuthListener();

  const [dialogs, setDialogs] = useState<MainDialogState>(NO_DIALOGS_OPEN);
  const openDialogs = useCallback(
    (patch: Partial<MainDialogState>) => setDialogs((current) => ({ ...current, ...patch })),
    [],
  );



  const drawerOpen = useUiStore((s) => s.drawerOpen);
  const sidebarCollapsed = useUiStore((s) => s.sidebarCollapsed);

  const viewColumns = useUiStore((s) => s.viewColumns);

  const listScrolled = useUiStore((s) => s.listScrolled);

  const setDrawerOpen = useUiStore((s) => s.setDrawerOpen);
  const toggleSidebarCollapsed = useUiStore((s) => s.toggleSidebarCollapsed);

  const setViewColumns = useUiStore((s) => s.setViewColumns);
  const cycleViewColumns = useUiStore((s) => s.cycleViewColumns);

  const setListScrolled = useUiStore((s) => s.setListScrolled);

  const openNewNote = useUiStore((s) => s.openNewNote);

  const openNote = useUiStore((s) => s.openNote);
  const openAuthScreen = useUiStore((s) => s.openAuthScreen);
  const setLabelsOpen = useUiStore((s) => s.setLabelsOpen);
  const recentSearches = useUiStore((s) => s.recentSearches);
  const addRecentSearch = useUiStore((s) => s.addRecentSearch);
  const clearRecentSearches = useUiStore((s) => s.clearRecentSearches);

  const selectedNoteIds = useUiStore((s) => s.selectedNoteIds);
  const toggleNoteSelection = useUiStore((s) => s.toggleNoteSelection);
  const clearSelection = useUiStore((s) => s.clearSelection);
  const toggleSelectAll = useUiStore((s) => s.toggleSelectAll);

  const selectionMode = selectedNoteIds.length > 0;

  const theme = useSettingsStore((s) => s.theme);
  const setThemeBase = useSettingsStore((s) => s.setThemeBase);
  const setAccentColor = useSettingsStore((s) => s.setAccentColor);
  const setAmoled = useSettingsStore((s) => s.setAmoled);

  const cloud = useCloudSync();



  const {

    notes,

    filteredNotes,

    isFuzzyResult,

    labels,

    filters,

    setSearchQuery,

    setColorFilter,

    setLabelFilter,

    setNoteFilter,

    setSortOrder,

    clearFilters,

    isLoading,

    error,

  } = useNotes();



  const navCounts = useMemo(
    () => ({
      active: notes.filter((note) => !note.isArchived && !note.isTrashed).length,
      archived: notes.filter((note) => note.isArchived && !note.isTrashed).length,
      trashed: notes.filter((note) => note.isTrashed).length,
    }),
    [notes],
  );



  const hasActiveFilters =

    Boolean(filters.searchQuery) ||

    filters.colorArgb != null ||

    filters.labelName != null;

  const filteredNoteIds = useMemo(() => filteredNotes.map((note) => note.id), [filteredNotes]);

  const allFilteredSelected =
    filteredNotes.length > 0 &&
    filteredNotes.every((note) => selectedNoteIds.includes(note.id));

  const selectedNoteModels = useMemo(
    () =>
      selectedNoteIds
        .map((id) => notes.find((note) => note.id === id))
        .filter((note): note is Note => note != null),
    [notes, selectedNoteIds],
  );

  const selectionAllPinned =
    selectedNoteModels.length > 0 && selectedNoteModels.every((note) => note.isPinned);

  const allowReorder =
    filters.filter === 'active' &&
    (filters.sortOrder ?? 'manual') === 'manual' &&
    !hasActiveFilters &&
    !selectionMode &&
    viewColumns === 1;

  const handleNavigateFilter = (filter: NoteFilter) => {
    clearSelection();
    setNoteFilter(filter);
  };

  const getSelectedSnapshots = () =>
    selectedNoteIds
      .map((id) => notes.find((note) => note.id === id))
      .filter((note): note is Note => note != null)
      .map((note) => ({ ...note }));

  const handleNoteClick = (note: Note) => {
    if (selectionMode) {
      toggleNoteSelection(note.id);
      return;
    }
    openNote(note.id);
  };

  const handleNoteLongPress = (note: Note) => {
    if (!selectedNoteIds.includes(note.id)) {
      toggleNoteSelection(note.id);
    }
  };



  const handleScroll = useCallback(() => {

    const element = scrollRef.current;

    if (!element) return;

    setListScrolled(element.scrollTop > 0);

  }, [setListScrolled]);



  const cycleSortOrder = () => {

    const index = SORT_ORDERS.indexOf(filters.sortOrder ?? 'manual');

    const next = SORT_ORDERS[(index + 1) % SORT_ORDERS.length];

    setSortOrder(next);

    useToastStore.getState().show(`Sorted: ${SORT_ORDER_LABELS[next]}`);

  };



  const {
    archiveNote,
    trashNote,
    restoreNote,
    permanentlyDeleteNote,
    emptyTheTrash,
    bulkPinToggle,
    bulkArchive,
    bulkTrash,
    bulkRestore,
    bulkPermanentDelete,
    moveNote,
    reorderComplete,
  } = useNoteActions({
    notes,
    filteredNotes,
    getSelectedSnapshots,
    selectionAllPinned,
    clearSelection,
    closeEmptyTrashConfirm: () => openDialogs({ emptyTrashConfirm: false }),
    closeBulkDeleteConfirm: () => openDialogs({ bulkDeleteConfirm: false }),
  });

  const { signOut, exportBackup, importBackup } = useAccountActions({
    notes,
    userId: user?.uid,
    closeSignOutConfirm: () => openDialogs({ signOutConfirm: false }),
    closeProfile: () => openDialogs({ profile: false }),
  });

  const emptyState = getEmptyState(filters.filter, hasActiveFilters, Boolean(filters.searchQuery));

  const isTabletUp = useIsTabletUp();
  const editorRoute = useUiStore((s) => s.editorRoute);
  const editorLayout = useUiStore((s) => s.editorLayout);
  const desktopEditor = isTabletUp && editorRoute.mode !== 'closed' ? editorRoute : null;
  const dockedEditor =
    desktopEditor && editorLayout === 'dock' ? desktopEditor : null;
  const overlayEditor =
    desktopEditor && editorLayout !== 'dock' ? desktopEditor : null;

  useShortcuts([
    {
      key: '/',
      action: () => {
        if (editorRoute.mode === 'closed') searchInputRef.current?.focus();
      },
    },
    {
      key: 'n',
      action: () => {
        if (editorRoute.mode === 'closed' && filters.filter === 'active') openNewNote();
      },
    },
    {
      key: 'Escape',
      action: () => {
        if (editorRoute.mode !== 'closed') {
          return;
        }
        if (selectionMode) clearSelection();
        else setDrawerOpen(false);
      },
    },
  ]);

  return (
    <div className="flex min-h-screen w-full bg-true-surface lg:mx-auto lg:max-w-shell">

      <SideDrawer
        open={drawerOpen}
        collapsed={sidebarCollapsed}
        currentFilter={filters.filter}
        onClose={() => setDrawerOpen(false)}
        onToggleCollapse={toggleSidebarCollapsed}
        onNavigate={handleNavigateFilter}

        userEmail={user?.email ?? null}

        onSignIn={() => openAuthScreen('signin')}

        onSignOut={() => openDialogs({ signOutConfirm: true })}

        onEditLabels={() => setLabelsOpen(true)}

        navCounts={navCounts}

        onOpenSettings={() => openDialogs({ profile: true })}

      />



      <div className="flex min-h-screen min-w-0 flex-1">
        <div className={`flex min-w-0 flex-1 flex-col transition-all duration-300 ${dockedEditor ? 'max-w-[min(32rem,46%)] border-r border-brand-outline xl:max-w-[min(36rem,42%)]' : ''}`}>
          <TopBar
            searchQuery={filters.searchQuery ?? ''}

          onSearchQueryChange={setSearchQuery}

          currentFilter={filters.filter}

          listScrolled={listScrolled}

          sortOrder={filters.sortOrder ?? 'manual'}

          onSortOrderCycle={cycleSortOrder}

          selectedColor={filters.colorArgb ?? null}

          onColorSelect={setColorFilter}

          labels={labels}

          selectedLabelName={filters.labelName ?? null}

          onLabelSelect={setLabelFilter}

          hasActiveFilters={hasActiveFilters}

          onClearFilters={clearFilters}

          onMenuClick={() => setDrawerOpen(true)}

          onProfileClick={() => openDialogs({ profile: true })}
          viewColumns={viewColumns}
          onViewColumnsChange={setViewColumns}
          onNewNote={openNewNote}
          showNewNote={filters.filter === 'active'}
          selectionMode={selectionMode}
          selectedCount={selectedNoteIds.length}
          allFilteredSelected={allFilteredSelected}
          onClearSelection={clearSelection}
          onToggleSelectAll={() => toggleSelectAll(filteredNoteIds)}
          selectionAllPinned={selectionAllPinned}
          onBulkPinToggle={() => void bulkPinToggle()}
          onBulkArchive={() => void bulkArchive()}
          onBulkRestore={() => void bulkRestore()}
          onBulkTrash={() => void bulkTrash()}
          onBulkPermanentDelete={() => openDialogs({ bulkDeleteConfirm: true })}
          recentSearches={recentSearches}
          onRecentSearchClick={(query) => {
            setSearchQuery(query);
            addRecentSearch(query);
          }}
          onClearRecentSearches={clearRecentSearches}
          searchInputRef={searchInputRef}
        />



        <OfflineBanner />

        <InstallPrompt />

        {filters.filter === 'trashed' && filteredNotes.length > 0 ? (
          <TrashBanner onEmptyTrash={() => openDialogs({ emptyTrashConfirm: true })} />
        ) : null}



        <main

          ref={scrollRef}

          onScroll={handleScroll}

          className="flex-1 overflow-y-auto overscroll-contain"

        >

          <div className="mx-auto w-full max-w-content">

            {error ? (
              <div className="px-4 py-6 text-center">
                <p className="text-sm text-red-500 dark:text-red-400 mb-3">{error}</p>
                <button
                  type="button"
                  onClick={() => window.location.reload()}
                  className="rounded-full border border-brand-outline/50 bg-brand-primary/10 px-4 py-2 text-sm font-semibold text-brand-primary transition-colors hover:bg-brand-primary/15 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary"
                >
                  Retry
                </button>
              </div>
            ) : null}

            {isLoading ? (
              <NotesLoadingGrid viewPreference={viewColumns} />
            ) : filteredNotes.length === 0 ? (

              <NotesEmptyState
                message={emptyState.message}
                subtitle={emptyState.subtitle}
                icon={emptyState.icon}
                recentSearches={recentSearches}
                onRecentSearchClick={(query) => {
                  setSearchQuery(query);
                  addRecentSearch(query);
                }}
                action={
                  emptyState.showClearFilters ? (
                    <button
                      type="button"
                      onClick={clearFilters}
                      className="min-h-11 rounded-note border border-brand-outline/50 px-5 py-2.5 text-sm font-semibold text-brand-primary transition-colors hover:bg-brand-primary/5"
                    >
                      Clear filters
                    </button>
                  ) : emptyState.showCreate ? (
                    <button
                      type="button"
                      onClick={openNewNote}
                      className="min-h-11 rounded-note bg-brand-primary px-5 py-2.5 text-sm font-semibold text-true-surface transition-transform active:scale-95"
                    >
                      New note
                    </button>
                  ) : undefined
                }

              />

            ) : (
              <>
                {isFuzzyResult ? (
                  <SearchNotice query={filters.searchQuery ?? ''} />
                ) : null}
                <NoteStaggeredGrid
                  notes={filteredNotes}
                  viewPreference={viewColumns}
                  filter={filters.filter}
                  sortOrder={filters.sortOrder ?? 'manual'}
                  onNoteClick={handleNoteClick}
                onNoteLongPress={handleNoteLongPress}
                selectedNoteIds={selectedNoteIds}
                selectionMode={selectionMode}
                onLabelClick={(name) => {
                  setNoteFilter('active');
                  setLabelFilter(name);
                }}
                listActions={{
                  onArchive: (note) => void archiveNote(note),
                  onTrash: (note) => void trashNote(note),
                  onRestore: (note) => void restoreNote(note),
                  onPermanentDelete: (note) => void permanentlyDeleteNote(note),
                }}
                searchQuery={filters.searchQuery ?? ''}
                allowReorder={allowReorder}
                onMoveNote={moveNote}
                onReorderComplete={reorderComplete}
              />
              </>
            )}

          </div>

        </main>



        {filters.filter === 'active' && !selectionMode ? (

          <button

            type="button"

            onClick={openNewNote}

            className="fixed z-20 flex size-14 items-center justify-center rounded-note bg-brand-primary text-true-surface shadow-lg transition-transform hover:scale-105 active:scale-95 bottom-[calc(1.5rem+env(safe-area-inset-bottom,0px))] right-[calc(1.5rem+env(safe-area-inset-right,0px))] md:hidden"

            aria-label="Add note"

          >

            <AddIcon size={28} />

          </button>
        ) : null}
        </div>

        {dockedEditor ? (
          // No animate-in here: EditorScreen renders fixed-position descendants
          // (EditorOptionsSheet, LinkDialog, ReminderPickerDialog), and tailwindcss-animate's
          // shared `enter` keyframe always applies a transform — even for plain fade-in —
          // which would make this non-fixed wrapper a containing block for those fixed
          // descendants for the animation's duration, mispositioning them if opened mid-animation.
          <div className="relative flex-1 bg-true-surface">
            <ErrorBoundary
              variant="overlay"
              allowClearData={false}
              onDismiss={() => useUiStore.getState().closeEditor()}
            >
              <Suspense fallback={null}>
                <EditorScreen
                  key={dockedEditor.mode === 'new' ? 'new' : dockedEditor.noteId}
                  route={dockedEditor}
                />
              </Suspense>
            </ErrorBoundary>
          </div>
        ) : null}

        {overlayEditor ? (
          <ErrorBoundary
            variant="overlay"
            allowClearData={false}
            onDismiss={() => useUiStore.getState().closeEditor()}
          >
            <Suspense fallback={null}>
              <EditorScreen
                key={overlayEditor.mode === 'new' ? 'new' : overlayEditor.noteId}
                route={overlayEditor}
              />
            </Suspense>
          </ErrorBoundary>
        ) : null}
      </div>



      <MainDialogs
        open={dialogs}
        onOpenChange={openDialogs}
        noteCount={notes.length}
        trashedCount={navCounts.trashed}
        selectedCount={selectedNoteIds.length}
        viewColumns={viewColumns}
        onViewColumnsCycle={cycleViewColumns}
        sortOrder={filters.sortOrder ?? 'manual'}
        onSortOrderCycle={cycleSortOrder}
        theme={theme}
        onThemeBaseChange={setThemeBase}
        onAccentChange={setAccentColor}
        onAmoledChange={setAmoled}
        isGoogleAccount={cloud.isGoogleAccount}
        isGuest={cloud.isGuest}
        userEmail={cloud.userEmail}
        syncStatus={cloud.status}
        syncedNoteCount={cloud.syncedCount}
        onExportBackup={exportBackup}
        onImportBackup={() => backupInputRef.current?.click()}
        onSignIn={() => openAuthScreen('signin')}
        onSignUp={() => openAuthScreen('signup')}
        onSignOut={(deleteCloudData) => void signOut(deleteCloudData)}
        onEmptyTrash={() => void emptyTheTrash()}
        onBulkPermanentDelete={() => void bulkPermanentDelete()}
      />

      <ToastHost />



      <input

        ref={backupInputRef}

        type="file"

        accept="application/json,.json"

        className="hidden"

        onChange={(event) => {

          const file = event.target.files?.[0];

          event.target.value = '';

          if (file) void importBackup(file);

        }}

      />

    </div>

  );

}
