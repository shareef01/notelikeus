import { beforeEach, describe, expect, it, vi } from 'vitest';

const clearOwnerMock = vi.fn().mockResolvedValue(undefined);

vi.mock('@/lib/local/notesLocalRepository', () => ({
  clearOwner: (...args: unknown[]) => clearOwnerMock(...args),
}));

import {
  clearLocalUserData,
  clearLocalUserDataForAccountSwitch,
} from '@/lib/bootstrap';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';

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
