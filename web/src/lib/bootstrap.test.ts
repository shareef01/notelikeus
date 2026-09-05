import { beforeEach, describe, expect, it, vi } from 'vitest';

const clearOwnerMock = vi.fn().mockResolvedValue(undefined);

vi.mock('@/lib/local/notesLocalRepository', () => ({
  clearOwner: (...args: unknown[]) => clearOwnerMock(...args),
}));

import {
  clearLocalUserData,
  clearLocalUserDataForAccountSwitch,
} from '@/lib/bootstrap';
import { useAuthStore } from '@/store/authStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

describe('clearLocalUserData', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useLabelRegistryStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('does not wipe IndexedDB on sign-out so offline edits can survive re-login', () => {
    clearLocalUserData();
    expect(clearOwnerMock).not.toHaveBeenCalled();
  });
});

describe('clearLocalUserDataForAccountSwitch', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useLabelRegistryStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it('clears the previous account IndexedDB namespace on account switch', () => {
    clearLocalUserDataForAccountSwitch('user-a');
    expect(clearOwnerMock).toHaveBeenCalledWith('user-a');
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
