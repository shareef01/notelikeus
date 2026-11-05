import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { mocks } = vi.hoisted(() => ({
  mocks: {
    hydrateIndexedDbFromRemote: vi.fn(),
    loadLocalNotesIntoStore: vi.fn(),
    clearLocalUserDataForAccountSwitch: vi.fn(),
  },
}));

vi.mock('@/lib/local/hydrateFromRemote', () => ({
  hydrateIndexedDbFromRemote: mocks.hydrateIndexedDbFromRemote,
  loadLocalNotesIntoStore: mocks.loadLocalNotesIntoStore,
}));
vi.mock('@/lib/bootstrap', () => ({
  clearLocalUserDataForAccountSwitch: mocks.clearLocalUserDataForAccountSwitch,
  clearLocalUserData: vi.fn(),
  clearPendingDeletions: vi.fn(),
}));
vi.mock('@/lib/local/adoptGuestNotes', () => ({ adoptGuestNotesIntoAccount: vi.fn() }));
vi.mock('@/lib/notes/legacyLocalMigration', () => ({ migrateLegacyLocalNotes: vi.fn() }));
vi.mock('@/lib/notes/notesSyncService', () => ({
  startNotesRealtimeSync: vi.fn(),
  stopNotesRealtimeSync: vi.fn(),
}));

import { useNotesSync } from '@/hooks/useNotesSync';
import { saveLastMergedUserId } from '@/lib/notes/lastMergedUser';
import { stashPendingDeletions } from '@/lib/notes/pendingDeletionStash';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { AuthUser } from '@/lib/auth/authUser';

let root: Root | null = null;

async function render(): Promise<void> {
  function TestComponent() {
    useNotesSync(true);
    return null;
  }
  await act(async () => {
    root = createRoot(document.createElement('div'));
    root.render(React.createElement(TestComponent));
  });
}

/**
 * AUDIT-004: deletions set aside when another account took over have to be back in force before
 * the first cloud snapshot, or the snapshot pulls the deleted notes straight back.
 */
describe('useNotesSync pending deletions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    localStorage.clear();
    useTombstoneStore.getState().reset();
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
  });

  afterEach(async () => {
    const current = root;
    root = null;
    if (current) await act(async () => current.unmount());
  });

  it('restores the signing-in account’s deletions before the first snapshot', async () => {
    useTombstoneStore.getState().markDeleted('note-a');
    stashPendingDeletions('user-a');
    saveLastMergedUserId('user-a');
    let deletedDuringSnapshot: boolean | null = null;
    mocks.hydrateIndexedDbFromRemote.mockImplementation(async () => {
      deletedDuringSnapshot = useTombstoneStore.getState().isDeleted('note-a');
    });

    useAuthStore.getState().setUser({ uid: 'user-a' } as AuthUser);
    useAuthStore.getState().setReady(true);
    await render();

    expect(mocks.hydrateIndexedDbFromRemote).toHaveBeenCalledWith('user-a');
    expect(deletedDuringSnapshot).toBe(true);
  });

  it('does not give another account’s deletions to the one signing in', async () => {
    useTombstoneStore.getState().markDeleted('note-a');
    stashPendingDeletions('user-a');
    saveLastMergedUserId('user-a');

    useAuthStore.getState().setUser({ uid: 'user-b' } as AuthUser);
    useAuthStore.getState().setReady(true);
    await render();

    expect(mocks.clearLocalUserDataForAccountSwitch).toHaveBeenCalledWith('user-a');
    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(false);
  });
});
