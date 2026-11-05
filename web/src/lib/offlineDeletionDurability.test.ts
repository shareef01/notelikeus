import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bootstrapApp,
  clearLocalUserData,
  clearLocalUserDataForAccountSwitch,
  clearPendingDeletions,
  clearPersistedAppData,
} from '@/lib/bootstrap';
import { restorePendingDeletions } from '@/lib/notes/pendingDeletionStash';
import { useTombstoneStore } from '@/store/tombstoneStore';

/**
 * A note deleted while offline exists nowhere afterwards except as a tombstone: the local row is
 * already gone, and the server copy is still live because the delete never reached it.
 *
 * Sign-out deliberately preserves IndexedDB notes so unsynced edits survive re-login. It used to
 * destroy tombstones anyway, so the same round trip resurrected deleted notes — the next
 * hydration pulled the still-live server copy straight back with nothing left to suppress it.
 */
describe('offline deletion durability across sign-out', () => {
  beforeEach(() => {
    useTombstoneStore.getState().reset();
    localStorage.clear();
  });

  it('keeps an unsynced deletion across a normal sign-out', () => {
    useTombstoneStore.getState().markDeleted('note-offline-deleted');

    clearLocalUserData();

    // The regression: without this the note comes back on the next sign-in.
    expect(useTombstoneStore.getState().isDeleted('note-offline-deleted')).toBe(true);
  });

  it('keeps the persisted tombstone record, not just the in-memory one', () => {
    useTombstoneStore.getState().markDeleted('note-offline-deleted');
    expect(localStorage.getItem('notelikeus-deleted-notes')).not.toBeNull();

    clearLocalUserData();

    // A reload after sign-out has to find it too, or the resurrection just moves one step later.
    expect(localStorage.getItem('notelikeus-deleted-notes')).not.toBeNull();
  });

  it('takes pending deletions out of use when a different account takes over', async () => {
    useTombstoneStore.getState().markDeleted('note-from-account-a');

    await clearLocalUserDataForAccountSwitch('owner-a');

    // The other half of the invariant: one account's deletions must never suppress another
    // account's notes.
    expect(useTombstoneStore.getState().isDeleted('note-from-account-a')).toBe(false);
  });

  it('drops pending deletions whose account is unknown', () => {
    useTombstoneStore.getState().markDeleted('note-from-nobody');

    clearPendingDeletions();

    expect(useTombstoneStore.getState().isDeleted('note-from-nobody')).toBe(false);
  });

  it('still forgets a deletion the server has acknowledged', () => {
    useTombstoneStore.getState().markDeleted('note-synced-delete');
    useTombstoneStore.getState().clearIds(['note-synced-delete']);

    clearLocalUserData();

    // Preserving intent must not mean keeping tombstones forever: once the server has the
    // deletion, holding one would block legitimately re-creating that id.
    expect(useTombstoneStore.getState().isDeleted('note-synced-delete')).toBe(false);
  });

  it('preserves last merged user id across normal sign out so the next account switch sets tombstones aside', async () => {
    const { loadLastMergedUserId, saveLastMergedUserId } = await import('@/lib/notes/lastMergedUser');

    // 1. User A is active and deletes a note offline
    saveLastMergedUserId('user-a');
    useTombstoneStore.getState().markDeleted('note-offline-deleted-by-a');

    // 2. User A signs out normally
    clearLocalUserData();

    // Tombstones stay for user A re-login:
    expect(useTombstoneStore.getState().isDeleted('note-offline-deleted-by-a')).toBe(true);

    // But last merged user ID is preserved so account switch can be detected:
    expect(loadLastMergedUserId()).toBe('user-a');

    // 3. User B signs in -> useNotesSync checks loadLastMergedUserId()
    const incomingUser = 'user-b';
    const lastMerged = loadLastMergedUserId();
    if (lastMerged != null && lastMerged !== incomingUser) {
      await clearLocalUserDataForAccountSwitch(lastMerged);
    }

    // After account switch, User A's tombstones are out of use and cannot suppress User B's notes:
    expect(useTombstoneStore.getState().isDeleted('note-offline-deleted-by-a')).toBe(false);
    expect(loadLastMergedUserId()).toBeNull();
  });
});

/**
 * AUDIT-004: an account switch or guest mode used to destroy the previous account's pending
 * deletions. Its notes deleted offline then came back on its next sync, from the still-live cloud
 * copy.
 */
describe('pending deletions across account switches (AUDIT-004)', () => {
  beforeEach(() => {
    useTombstoneStore.getState().reset();
    localStorage.clear();
  });

  it("keeps A's deletions through A -> B -> A", async () => {
    useTombstoneStore.getState().markDeleted('note-a', 7, 1_000);

    await clearLocalUserDataForAccountSwitch('user-a');
    restorePendingDeletions('user-b');
    // B never sees A's deletion.
    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(false);
    useTombstoneStore.getState().markDeleted('note-b');

    await clearLocalUserDataForAccountSwitch('user-b');
    restorePendingDeletions('user-a');

    const state = useTombstoneStore.getState();
    expect(state.isDeleted('note-a')).toBe(true);
    // The baseline the delete is sent against survives too, or the server refuses it.
    expect(state.baselineRevisionById['note-a']).toBe(7);
    expect(state.deletedAtById['note-a']).toBe(1_000);
    // And A does not pick up B's.
    expect(state.isDeleted('note-b')).toBe(false);
  });

  it('keeps them across a reload while set aside', async () => {
    useTombstoneStore.getState().markDeleted('note-a');
    await clearLocalUserDataForAccountSwitch('user-a');

    // A reload empties memory; only localStorage is left.
    useTombstoneStore.getState().reset();
    restorePendingDeletions('user-a');

    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(true);
  });

  it('restores them once, then forgets the set-aside copy', async () => {
    useTombstoneStore.getState().markDeleted('note-a');
    await clearLocalUserDataForAccountSwitch('user-a');
    restorePendingDeletions('user-a');
    useTombstoneStore.getState().clearIds(['note-a']); // the server acknowledged it

    restorePendingDeletions('user-a');

    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(false);
  });

  it("keeps A's deletions when A enters guest mode and later signs in again", async () => {
    const { saveLastMergedUserId } = await import('@/lib/notes/lastMergedUser');
    const { useAuthStore } = await import('@/store/authStore');
    saveLastMergedUserId('user-a');
    useTombstoneStore.getState().markDeleted('note-a');

    useAuthStore.getState().enterGuestMode();
    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(false);

    useAuthStore.getState().exitGuestMode();
    restorePendingDeletions('user-a');
    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(true);
  });

  it('still clears them on an explicit clear of local data', async () => {
    useTombstoneStore.getState().markDeleted('note-a');
    await clearLocalUserDataForAccountSwitch('user-a');

    clearPersistedAppData();
    restorePendingDeletions('user-a');

    expect(useTombstoneStore.getState().isDeleted('note-a')).toBe(false);
  });
});

/** AUDIT-007: the automatic recovery from a failed store load used to remove the tombstone record. */
describe('startup recovery keeps pending deletions (AUDIT-007)', () => {
  beforeEach(() => {
    useTombstoneStore.getState().reset();
    localStorage.clear();
  });

  it('keeps the tombstone record when another store fails to load', async () => {
    const { useNotesStore } = await import('@/store/notesStore');
    useTombstoneStore.getState().markDeleted('note-offline-deleted');
    const persisted = localStorage.getItem('notelikeus-deleted-notes');
    expect(persisted).not.toBeNull();
    const rehydrate = vi
      .spyOn(useNotesStore.persist, 'rehydrate')
      .mockRejectedValueOnce(new Error('filters record is unreadable'));

    // The configuration checks after recovery may refuse to boot in tests; recovery has run by then.
    await bootstrapApp().catch(() => undefined);

    expect(rehydrate).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem('notelikeus-deleted-notes')).toBe(persisted);
    expect(useTombstoneStore.getState().isDeleted('note-offline-deleted')).toBe(true);
    rehydrate.mockRestore();
  });
});
