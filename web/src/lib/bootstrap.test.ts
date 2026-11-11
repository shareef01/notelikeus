import { beforeEach, describe, expect, it, vi } from 'vitest';

const clearOwnerMock = vi.fn().mockResolvedValue(undefined);
const clearPendingAttachmentsMock = vi.fn().mockResolvedValue(undefined);
const countPendingPushesMock = vi.fn().mockResolvedValue(0);
const countPendingAttachmentsMock = vi.fn().mockResolvedValue(0);

vi.mock('@/lib/local/notesLocalRepository', () => ({
  clearOwner: (...args: unknown[]) => clearOwnerMock(...args),
  countPendingPushes: (...args: unknown[]) => countPendingPushesMock(...args),
}));

vi.mock('@/lib/local/pendingAttachmentRepository', () => ({
  clearPendingAttachmentsForOwner: (...args: unknown[]) => clearPendingAttachmentsMock(...args),
  countPendingAttachmentsForOwner: (...args: unknown[]) => countPendingAttachmentsMock(...args),
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

  it('forgets saved filters at sign-out but keeps device preferences (native NL-02)', () => {
    useUiStore.getState().setViewColumns(3);
    useUiStore.getState().saveFilter('Invoices', { filter: 'active', labelNames: ['Money'] });

    clearLocalUserData();

    expect(useUiStore.getState().savedFilters).toEqual([]);
    expect(useUiStore.getState().viewColumns).toBe(3);
  });
});

describe('saved filters across an account switch', () => {
  it('are not inherited by the next account', async () => {
    useUiStore.getState().saveFilter('Invoices', { filter: 'active', flags: ['HAS_LINKS'] });

    await clearLocalUserDataForAccountSwitch('user-a');

    expect(useUiStore.getState().savedFilters).toEqual([]);
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

  // FU-1: the previous account's namespace is the only copy of edits the cloud never confirmed.
  it("keeps the previous account's local notes while it has edits not yet uploaded", async () => {
    countPendingPushesMock.mockResolvedValueOnce(2);
    useNotesStore.getState().setNotes([createEmptyNote({ id: '1', localId: 1, title: 'A' })]);

    await clearLocalUserDataForAccountSwitch('user-a');

    expect(countPendingPushesMock).toHaveBeenCalledWith('user-a');
    expect(clearOwnerMock).not.toHaveBeenCalled();
    expect(clearPendingAttachmentsMock).not.toHaveBeenCalled();
    // The session state is still cleared: the next account never sees A's notes in memory.
    expect(useNotesStore.getState().notes).toEqual([]);
  });

  // AUDIT-002: a note can be fully synced while an image it references never uploaded. The staged
  // copy is then the only one, so an account switch must not delete it.
  it("keeps the previous account's data while it has staged images, even with no pending edit", async () => {
    countPendingAttachmentsMock.mockResolvedValueOnce(1);

    await clearLocalUserDataForAccountSwitch('user-a');

    expect(countPendingAttachmentsMock).toHaveBeenCalledWith('user-a');
    expect(clearOwnerMock).not.toHaveBeenCalled();
    expect(clearPendingAttachmentsMock).not.toHaveBeenCalled();
  });

  // AUDIT-006: a count that could not be read is not zero.
  it.each([
    ['pending edits', () => countPendingPushesMock.mockRejectedValueOnce(new Error('meta read failed'))],
    ['staged images', () => countPendingAttachmentsMock.mockRejectedValueOnce(new Error('index read failed'))],
  ])("keeps the previous account's data when its %s cannot be counted", async (_what, fail) => {
    fail();

    await expect(clearLocalUserDataForAccountSwitch('user-a')).resolves.toBeUndefined();

    expect(clearOwnerMock).not.toHaveBeenCalled();
    expect(clearPendingAttachmentsMock).not.toHaveBeenCalled();
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
