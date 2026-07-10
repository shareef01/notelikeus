import { AddIcon } from '@/components/icons/Icons';

import { ToastHost } from '@/components/feedback/ToastHost';
import { InstallPrompt } from '@/components/layout/InstallPrompt';
import { OfflineBanner } from '@/components/layout/OfflineBanner';
import { SideDrawer } from '@/components/layout/SideDrawer';
import { TopBar } from '@/components/layout/TopBar';
import { NoteStaggeredGrid } from '@/components/notes/NoteStaggeredGrid';
import { NotesEmptyState } from '@/components/notes/NotesEmptyState';
import { BulkDeleteDialog } from '@/components/notes/BulkDeleteDialog';
import { EmptyTrashDialog } from '@/components/notes/EmptyTrashDialog';
import { NotesLoadingGrid } from '@/components/notes/NotesLoadingGrid';
import { TrashBanner } from '@/components/notes/TrashBanner';

import { ProfileSheet, THEME_ORDER } from '@/components/settings/ProfileSheet';

import { PrivacyPolicyDialog } from '@/components/settings/PrivacyPolicyDialog';

import { SignOutDialog } from '@/components/settings/SignOutDialog';

import { useAuthListener } from '@/hooks/useAuth';

import { useCloudSync } from '@/hooks/useCloudSync';

import { useEffectiveColumns } from '@/hooks/useEffectiveColumns';

import { useNotes } from '@/hooks/useNotes';

import { exportNotesBackup } from '@/lib/backup/exportBackup';

import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';

import { signOutGoogle } from '@/lib/auth/googleAuth';
import {
  archiveNoteById,
  emptyTrash,
  removeNote,
  restoreNoteById,
  saveNote,
  trashNoteById,
} from '@/lib/notes/noteActions';
import { showUndoToast } from '@/lib/notes/showUndoToast';
import { commitNotePositions, previewMoveNote } from '@/lib/notes/noteOrder';

import { uploadAllNotes } from '@/lib/firestore/notesRepository';

import { useNotesStore } from '@/store/notesStore';

import { useSettingsStore } from '@/store/settingsStore';

import { useToastStore } from '@/store/toastStore';

import { useUiStore } from '@/store/uiStore';

import type { Note, NoteFilter } from '@/types/note';

import { useIsDesktop } from '@/hooks/useMediaQuery';
import { EditorScreen } from '@/screens/EditorScreen';

import { useCallback, useMemo, useRef, useState } from 'react';



const SORT_ORDERS = ['manual', 'newest', 'oldest'] as const;



export function MainScreen() {

  const scrollRef = useRef<HTMLDivElement>(null);

  const backupInputRef = useRef<HTMLInputElement>(null);

  const { user } = useAuthListener();

  const [showProfile, setShowProfile] = useState(false);

  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);

  const [showPrivacyPolicy, setShowPrivacyPolicy] = useState(false);
  const [showEmptyTrashConfirm, setShowEmptyTrashConfirm] = useState(false);
  const [showBulkDeleteConfirm, setShowBulkDeleteConfirm] = useState(false);



  const drawerOpen = useUiStore((s) => s.drawerOpen);

  const viewColumns = useUiStore((s) => s.viewColumns);

  const listScrolled = useUiStore((s) => s.listScrolled);

  const setDrawerOpen = useUiStore((s) => s.setDrawerOpen);

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



  const effectiveColumns = useEffectiveColumns(viewColumns);



  const cloudAutoSyncEnabled = useSettingsStore((s) => s.cloudAutoSyncEnabled);
  const setCloudAutoSyncEnabled = useSettingsStore((s) => s.setCloudAutoSyncEnabled);
  const appTheme = useSettingsStore((s) => s.appTheme);
  const setAppTheme = useSettingsStore((s) => s.setAppTheme);

  const cloud = useCloudSync();



  const {

    notes,

    filteredNotes,

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

  const allowReorder =
    filters.filter === 'active' &&
    (filters.sortOrder ?? 'manual') === 'manual' &&
    !hasActiveFilters &&
    !selectionMode &&
    effectiveColumns === 1;

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

  };



  const handleArchiveNote = async (note: Note) => {
    const previous = { ...note };
    await archiveNoteById(note.id);
    showUndoToast({
      message: 'Note archived',
      revert: () => saveNote(previous),
    });
  };



  const handleTrashNote = async (note: Note) => {
    const previous = { ...note };
    await trashNoteById(note.id);
    showUndoToast({
      message: 'Note moved to trash',
      revert: () => saveNote(previous),
    });
  };



  const handleRestoreNote = async (note: Note) => {
    await restoreNoteById(note.id);
    useToastStore.getState().show('Note restored');
  };



  const handlePermanentDelete = async (note: Note) => {
    await removeNote(note.id);
    useToastStore.getState().show('Note deleted permanently');
  };



  const handleEmptyTrash = async () => {
    setShowEmptyTrashConfirm(false);
    const count = await emptyTrash();
    useToastStore.getState().show(
      count > 0 ? `Deleted ${count} note${count === 1 ? '' : 's'} permanently` : 'Trash is already empty',
    );
  };

  const handleBulkPin = async () => {
    const snapshots = getSelectedSnapshots();
    if (snapshots.length === 0) return;
    for (const note of snapshots) {
      await saveNote({ ...note, isPinned: true, timestamp: Date.now() });
    }
    clearSelection();
    useToastStore.getState().show(
      `${snapshots.length} note${snapshots.length === 1 ? '' : 's'} pinned`,
    );
  };

  const handleBulkUnpin = async () => {
    const snapshots = getSelectedSnapshots();
    if (snapshots.length === 0) return;
    for (const note of snapshots) {
      await saveNote({ ...note, isPinned: false, timestamp: Date.now() });
    }
    clearSelection();
    useToastStore.getState().show(
      `${snapshots.length} note${snapshots.length === 1 ? '' : 's'} unpinned`,
    );
  };

  const handleMoveNote = (fromIndex: number, toIndex: number) => {
    const reordered = previewMoveNote(notes, filteredNotes, fromIndex, toIndex);
    if (reordered) {
      useNotesStore.getState().setNotes(reordered);
    }
  };

  const handleReorderComplete = () => {
    void commitNotePositions(useNotesStore.getState().notes);
  };

  const handleBulkArchive = async () => {
    const snapshots = getSelectedSnapshots();
    if (snapshots.length === 0) return;
    for (const note of snapshots) {
      await archiveNoteById(note.id);
    }
    clearSelection();
    showUndoToast({
      message: `${snapshots.length} note${snapshots.length === 1 ? '' : 's'} archived`,
      revert: async () => {
        for (const note of snapshots) {
          await saveNote(note);
        }
      },
    });
  };

  const handleBulkTrash = async () => {
    const snapshots = getSelectedSnapshots();
    if (snapshots.length === 0) return;
    for (const note of snapshots) {
      await trashNoteById(note.id);
    }
    clearSelection();
    showUndoToast({
      message: `${snapshots.length} note${snapshots.length === 1 ? '' : 's'} moved to trash`,
      revert: async () => {
        for (const note of snapshots) {
          await saveNote(note);
        }
      },
    });
  };

  const handleBulkRestore = async () => {
    const snapshots = getSelectedSnapshots();
    if (snapshots.length === 0) return;
    for (const note of snapshots) {
      await restoreNoteById(note.id);
    }
    clearSelection();
    useToastStore.getState().show(
      `${snapshots.length} note${snapshots.length === 1 ? '' : 's'} restored`,
    );
  };

  const handleBulkPermanentDelete = async () => {
    setShowBulkDeleteConfirm(false);
    const snapshots = getSelectedSnapshots();
    if (snapshots.length === 0) return;
    for (const note of snapshots) {
      await removeNote(note.id);
    }
    clearSelection();
    useToastStore.getState().show(
      `${snapshots.length} note${snapshots.length === 1 ? '' : 's'} deleted permanently`,
    );
  };



  const handleSignOut = async (deleteCloudData: boolean) => {

    setShowSignOutConfirm(false);

    setShowProfile(false);

    try {

      await signOutGoogle(deleteCloudData);

      useToastStore.getState().show(

        deleteCloudData ? 'Signed out and cloud data deleted' : 'Signed out',

      );

    } catch (error) {

      useToastStore.getState().show(

        error instanceof Error ? error.message : 'Sign out failed',

        'error',

      );

    }

  };



  const handleImportBackup = async (file: File) => {

    try {

      const json = await readBackupFile(file);

      const { merged, result } = importNotesFromBackup(json, notes);

      useNotesStore.getState().setNotes(merged);



      if (user?.uid) {

        await uploadAllNotes(user.uid, merged);

      }



      const parts: string[] = [];

      if (result.notesImported > 0) {

        parts.push(

          `${result.notesImported} note${result.notesImported === 1 ? '' : 's'}`,

        );

      }

      if (result.labelsCreated > 0) {

        parts.push(

          `${result.labelsCreated} label${result.labelsCreated === 1 ? '' : 's'}`,

        );

      }

      useToastStore.getState().show(

        parts.length > 0 ? `Imported ${parts.join(' and ')}` : 'No notes found in backup',

      );

    } catch (error) {

      useToastStore.getState().show(

        error instanceof Error ? error.message : 'Import failed',

        'error',

      );

    }

  };



  const emptyState = getEmptyState(filters.filter, hasActiveFilters, Boolean(filters.searchQuery));

  const isDesktop = useIsDesktop();
  const editorRoute = useUiStore((s) => s.editorRoute);
  const desktopEditor = isDesktop && editorRoute.mode !== 'closed' ? editorRoute : null;

  return (
    <div className="flex min-h-screen w-full bg-true-black lg:mx-auto lg:max-w-shell">

      <SideDrawer

        open={drawerOpen}

        currentFilter={filters.filter}

        onClose={() => setDrawerOpen(false)}

        onNavigate={handleNavigateFilter}

        userEmail={user?.email ?? null}

        onSignIn={() => openAuthScreen('signin')}

        onSignOut={() => setShowSignOutConfirm(true)}

        onEditLabels={() => setLabelsOpen(true)}

        navCounts={navCounts}

        onOpenSettings={() => setShowProfile(true)}

      />



      <div className="flex min-h-screen min-w-0 flex-1">
        <div className={`flex flex-col min-w-0 flex-1 transition-all duration-300 ${isDesktop && editorRoute.mode !== 'closed' ? 'max-w-[400px] border-r border-brand-outline' : ''}`}>
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

          onProfileClick={() => setShowProfile(true)}

          onViewModeCycle={cycleViewColumns}

          onNewNote={openNewNote}

          showNewNote={filters.filter === 'active'}

          viewColumns={effectiveColumns}

          selectionMode={selectionMode}
          selectedCount={selectedNoteIds.length}
          allFilteredSelected={allFilteredSelected}
          onClearSelection={clearSelection}
          onToggleSelectAll={() => toggleSelectAll(filteredNoteIds)}
          onBulkPin={() => void handleBulkPin()}
          onBulkUnpin={() => void handleBulkUnpin()}
          onBulkArchive={() => void handleBulkArchive()}
          onBulkRestore={() => void handleBulkRestore()}
          onBulkTrash={() => void handleBulkTrash()}
          onBulkPermanentDelete={() => setShowBulkDeleteConfirm(true)}
          recentSearches={recentSearches}
          onRecentSearchClick={(query) => {
            setSearchQuery(query);
            addRecentSearch(query);
          }}
          onClearRecentSearches={clearRecentSearches}
        />



        <OfflineBanner />

        <InstallPrompt />

        {filters.filter === 'trashed' && filteredNotes.length > 0 ? (
          <TrashBanner onEmptyTrash={() => setShowEmptyTrashConfirm(true)} />
        ) : null}



        <main

          ref={scrollRef}

          onScroll={handleScroll}

          className="flex-1 overflow-y-auto overscroll-contain"

        >

          <div className="mx-auto w-full max-w-content">

            {error ? (
              <div className="px-4 py-6 text-center text-sm text-red-300">{error}</div>
            ) : null}

            {isLoading ? (
              <NotesLoadingGrid columns={effectiveColumns} />
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
                  emptyState.showSignIn ? (
                    <div className="flex flex-col items-center gap-3 sm:flex-row">
                      <button
                        type="button"
                        onClick={() => openAuthScreen('signin')}
                        className="rounded-note bg-brand-primary px-5 py-2.5 text-sm font-semibold text-true-black"
                      >
                        Sign in
                      </button>
                      <button
                        type="button"
                        onClick={() => openAuthScreen('signup')}
                        className="rounded-note border border-brand-outline/50 px-5 py-2.5 text-sm font-semibold text-brand-primary"
                      >
                        Create account
                      </button>
                    </div>
                  ) : undefined
                }

              />

            ) : (
              <NoteStaggeredGrid
                notes={filteredNotes}
                columns={desktopEditor ? 1 : effectiveColumns}
                filter={filters.filter}
                onNoteClick={handleNoteClick}
                onNoteLongPress={handleNoteLongPress}
                selectedNoteIds={selectedNoteIds}
                selectionMode={selectionMode}
                onLabelClick={(name) => {
                  setNoteFilter('active');
                  setLabelFilter(name);
                }}
                listActions={{
                  onArchive: (note) => void handleArchiveNote(note),
                  onTrash: (note) => void handleTrashNote(note),
                  onRestore: (note) => void handleRestoreNote(note),
                  onPermanentDelete: (note) => void handlePermanentDelete(note),
                }}
                searchQuery={filters.searchQuery ?? ''}
                allowReorder={allowReorder}
                onMoveNote={handleMoveNote}
                onReorderComplete={handleReorderComplete}
              />
            )}

          </div>

        </main>



        {filters.filter === 'active' && !selectionMode ? (

          <button

            type="button"

            onClick={openNewNote}

            className="fixed bottom-6 right-6 z-20 flex size-14 items-center justify-center rounded-note bg-brand-primary text-true-black shadow-lg transition-transform hover:scale-105 active:scale-95 pb-safe pr-safe lg:hidden"

            aria-label="Add note"

          >

            <AddIcon size={28} />

          </button>
        ) : null}
        </div>

        {desktopEditor && (
          <div className="relative flex-1 bg-true-surface animate-in slide-in-from-right duration-300">
             <EditorScreen route={desktopEditor} />
          </div>
        )}
      </div>



      <ProfileSheet

        open={showProfile}

        onClose={() => setShowProfile(false)}

        noteCount={notes.length}

        viewColumns={viewColumns}
        sortOrder={filters.sortOrder ?? 'manual'}
        onViewColumnsCycle={cycleViewColumns}
        onSortOrderCycle={cycleSortOrder}
        appTheme={appTheme}
        onAppThemeCycle={() => {
            const currentIdx = THEME_ORDER.indexOf(appTheme);
            const next = THEME_ORDER[(currentIdx + 1) % THEME_ORDER.length];
            setAppTheme(next);
        }}
        cloudAutoSyncEnabled={cloudAutoSyncEnabled}

        onCloudAutoSyncChange={setCloudAutoSyncEnabled}

        isGoogleAccount={cloud.isGoogleAccount}

        userEmail={cloud.userEmail}

        syncStatus={cloud.status}

        syncedNoteCount={cloud.syncedCount}

        onSyncNow={() => void cloud.syncNow()}

        onRestore={() => void cloud.restoreFromCloud()}

        onExportBackup={() => exportNotesBackup(notes)}

        onImportBackup={() => backupInputRef.current?.click()}

        onPrivacyPolicy={() => setShowPrivacyPolicy(true)}

        onSignIn={() => openAuthScreen('signin')}

        onSignUp={() => openAuthScreen('signup')}

        onSignOut={() => setShowSignOutConfirm(true)}

        isSyncing={cloud.status === 'syncing'}

      />



      <SignOutDialog
        open={showSignOutConfirm}
        onCancel={() => setShowSignOutConfirm(false)}
        onSignOut={() => void handleSignOut(false)}
        onSignOutAndDelete={() => void handleSignOut(true)}
      />



      <BulkDeleteDialog
        open={showBulkDeleteConfirm}
        noteCount={selectedNoteIds.length}
        onCancel={() => setShowBulkDeleteConfirm(false)}
        onConfirm={() => void handleBulkPermanentDelete()}
      />

      <EmptyTrashDialog
        open={showEmptyTrashConfirm}
        noteCount={navCounts.trashed}
        onCancel={() => setShowEmptyTrashConfirm(false)}
        onConfirm={() => void handleEmptyTrash()}
      />



      <PrivacyPolicyDialog

        open={showPrivacyPolicy}

        onClose={() => setShowPrivacyPolicy(false)}

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

          if (file) void handleImportBackup(file);

        }}

      />

    </div>

  );

}



function getEmptyState(

  filter: NoteFilter,

  hasActiveFilters: boolean,

  hasSearch: boolean,

): {

  message: string;

  subtitle?: string;

  icon: 'brand' | 'archive' | 'trash';

  showSignIn?: boolean;

} {

  if (hasSearch) {

    return {

      message: 'No matching notes',

      subtitle: 'Try a different search term or clear filters',

      icon: 'brand',

    };

  }

  if (hasActiveFilters) {

    return {

      message: 'No notes match your filters',

      subtitle: 'Try another color or label',

      icon: 'brand',

    };

  }

  if (filter === 'archived') {

    return { message: 'No archived notes', icon: 'archive' };

  }

  if (filter === 'trashed') {

    return {

      message: 'No notes in trash',

      subtitle: 'Deleted notes are removed permanently',

      icon: 'trash',

    };

  }

  return {

    message: 'Notes you add appear here',

    subtitle: 'Sign in to sync with your Android device',

    icon: 'brand',

    showSignIn: true,

  };

}


