import { AddIcon } from '@/components/icons/Icons';

import { ToastHost } from '@/components/feedback/ToastHost';

import { SideDrawer } from '@/components/layout/SideDrawer';

import { TopBar } from '@/components/layout/TopBar';

import { NoteStaggeredGrid } from '@/components/notes/NoteStaggeredGrid';

import { NotesEmptyState } from '@/components/notes/NotesEmptyState';

import { ProfileSheet } from '@/components/settings/ProfileSheet';

import { PrivacyPolicyDialog } from '@/components/settings/PrivacyPolicyDialog';

import { SignOutDialog } from '@/components/settings/SignOutDialog';

import { useAuthListener } from '@/hooks/useAuth';

import { useCloudSync } from '@/hooks/useCloudSync';

import { useEffectiveColumns } from '@/hooks/useEffectiveColumns';

import { useNotes } from '@/hooks/useNotes';

import { exportNotesBackup } from '@/lib/backup/exportBackup';

import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';

import { signOutGoogle } from '@/lib/auth/googleAuth';

import { uploadAllNotes } from '@/lib/firestore/notesRepository';

import { useNotesStore } from '@/store/notesStore';

import { useSettingsStore } from '@/store/settingsStore';

import { useToastStore } from '@/store/toastStore';

import { useUiStore } from '@/store/uiStore';

import type { NoteFilter } from '@/types/note';

import { useCallback, useRef, useState } from 'react';



const SORT_ORDERS = ['manual', 'newest', 'oldest'] as const;



export function MainScreen() {

  const scrollRef = useRef<HTMLDivElement>(null);

  const backupInputRef = useRef<HTMLInputElement>(null);

  const { user } = useAuthListener();

  const [showProfile, setShowProfile] = useState(false);

  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);

  const [showPrivacyPolicy, setShowPrivacyPolicy] = useState(false);



  const drawerOpen = useUiStore((s) => s.drawerOpen);

  const viewColumns = useUiStore((s) => s.viewColumns);

  const listScrolled = useUiStore((s) => s.listScrolled);

  const setDrawerOpen = useUiStore((s) => s.setDrawerOpen);

  const cycleViewColumns = useUiStore((s) => s.cycleViewColumns);

  const setListScrolled = useUiStore((s) => s.setListScrolled);

  const openNewNote = useUiStore((s) => s.openNewNote);

  const openNote = useUiStore((s) => s.openNote);

  const openAuthScreen = useUiStore((s) => s.openAuthScreen);



  const effectiveColumns = useEffectiveColumns(viewColumns);



  const cloudAutoSyncEnabled = useSettingsStore((s) => s.cloudAutoSyncEnabled);

  const setCloudAutoSyncEnabled = useSettingsStore((s) => s.setCloudAutoSyncEnabled);

  const useMonochromeTheme = useSettingsStore((s) => s.useMonochromeTheme);

  const setUseMonochromeTheme = useSettingsStore((s) => s.setUseMonochromeTheme);

  const trueDarkMode = useSettingsStore((s) => s.trueDarkMode);

  const setTrueDarkMode = useSettingsStore((s) => s.setTrueDarkMode);



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

  } = useNotes();



  const hasActiveFilters =

    Boolean(filters.searchQuery) ||

    filters.colorArgb != null ||

    filters.labelName != null;



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



  return (

    <div className="flex min-h-screen w-full bg-true-black lg:mx-auto lg:max-w-shell">

      <SideDrawer

        open={drawerOpen}

        currentFilter={filters.filter}

        onClose={() => setDrawerOpen(false)}

        onNavigate={(filter: NoteFilter) => setNoteFilter(filter)}

        userEmail={user?.email ?? null}

        onSignIn={() => openAuthScreen('signin')}

        onSignOut={() => setShowSignOutConfirm(true)}

      />



      <div className="flex min-h-screen min-w-0 flex-1 flex-col">

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

        />



        <main

          ref={scrollRef}

          onScroll={handleScroll}

          className="flex-1 overflow-y-auto overscroll-contain"

        >

          <div className="mx-auto w-full max-w-content">

            {filteredNotes.length === 0 ? (

              <NotesEmptyState

                message={emptyState.message}

                subtitle={emptyState.subtitle}

                icon={emptyState.icon}

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

                columns={effectiveColumns}

                onNoteClick={(note) => openNote(note.id)}

              />

            )}

          </div>

        </main>



        {filters.filter === 'active' ? (

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



      <ProfileSheet

        open={showProfile}

        onClose={() => setShowProfile(false)}

        noteCount={notes.length}

        viewColumns={viewColumns}

        sortOrder={filters.sortOrder ?? 'manual'}

        onViewColumnsCycle={cycleViewColumns}

        onSortOrderCycle={cycleSortOrder}

        useMonochromeTheme={useMonochromeTheme}

        onMonochromeThemeChange={setUseMonochromeTheme}

        trueDarkMode={trueDarkMode}

        onTrueDarkModeChange={setTrueDarkMode}

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


