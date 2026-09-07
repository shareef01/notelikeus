import { beforeEach, describe, expect, it, vi } from 'vitest';

const clearOwnerMock = vi.fn().mockResolvedValue(undefined);
const clearPendingAttachmentsMock = vi.fn().mockResolvedValue(undefined);

vi.mock('@/lib/local/notesLocalRepository', () => ({
  clearOwner: (...args: unknown[]) => clearOwnerMock(...args),
}));

vi.mock('@/lib/local/pendingAttachmentRepository', () => ({
  clearPendingAttachmentsForOwner: (...args: unknown[]) => clearPendingAttachmentsMock(...args),
}));

import {
  clearLocalUserData,
  clearLocalUserDataForAccountSwitch,
} from '@/lib/bootstrap';
import { useAuthStore } from '@/store/authStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { useUiStore } from '@/store/uiStore';
import { createEmptyNote } from '@/types/note';

describe('clearLocalUserData', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useLabelRegistryStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('does not wipe IndexedDB or pending attachments on sign-out so offline edits survive re-login', () => {
    clearLocalUserData();
    expect(clearOwnerMock).not.toHaveBeenCalled();
    expect(clearPendingAttachmentsMock).not.toHaveBeenCalled();
  });

  it('closes the navigation drawer so it cannot cover the next account’s notes', () => {
    // Signing out is normally done from inside the drawer on mobile. It is a modal overlay, so
    // leaving it open across the switch put an aria-modal panel over the next session's notes
    // list, swallowing taps meant for it.
    useUiStore.getState().setDrawerOpen(true);

    clearLocalUserData();

    expect(useUiStore.getState().drawerOpen).toBe(false);
  });
});

describe('clearLocalUserDataForAccountSwitch', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useLabelRegistryStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('clears the previous account IndexedDB namespace and pending attachments on account switch', async () => {
    await clearLocalUserDataForAccountSwitch('user-a');
    expect(clearOwnerMock).toHaveBeenCalledWith('user-a');
    expect(clearPendingAttachmentsMock).toHaveBeenCalledWith('user-a');
  });

  it('surfaces storage failure when clearing prior account data fails', async () => {
    clearOwnerMock.mockRejectedValueOnce(new Error('IndexedDB clear failure'));
    await expect(clearLocalUserDataForAccountSwitch('user-a')).rejects.toThrow(
      /IndexedDB clear failure/,
    );
  });
});

describe('enterGuestMode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useLabelRegistryStore.getState().reset();
    useTombstoneStore.getState().reset();
    useAuthStore.getState().reset();
  });

  it('does not surface a previous account’s labels, tombstones, or in-memory notes', () => {
    useLabelRegistryStore.getState().addLabel('Therapist');
    useTombstoneStore.getState().markDeleted('99');
    useNotesStore.getState().setNotes([createEmptyNote({ id: '1', localId: 1, title: 'Secret' })]);

    useAuthStore.getState().enterGuestMode();

    expect(useAuthStore.getState().guestMode).toBe(true);
    expect(Object.keys(useLabelRegistryStore.getState().labels)).toHaveLength(0);
    expect(useTombstoneStore.getState().isDeleted('99')).toBe(false);
    expect(useNotesStore.getState().notes).toEqual([]);
    expect(clearOwnerMock).not.toHaveBeenCalled();
  });
});
