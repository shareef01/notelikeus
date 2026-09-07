import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearLocalUserData,
  clearLocalUserDataForAccountSwitch,
  clearPendingDeletions,
} from '@/lib/bootstrap';
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

  it('drops pending deletions when a different account takes over', async () => {
    useTombstoneStore.getState().markDeleted('note-from-account-a');

    await clearLocalUserDataForAccountSwitch('owner-a');

    // The other half of the invariant: one account's deletions must never suppress another
    // account's notes.
    expect(useTombstoneStore.getState().isDeleted('note-from-account-a')).toBe(false);
    expect(localStorage.getItem('notelikeus-deleted-notes')).toBeNull();
  });

  it('drops pending deletions when entering guest mode', () => {
    useTombstoneStore.getState().markDeleted('note-from-account-a');

    clearPendingDeletions();

    expect(useTombstoneStore.getState().isDeleted('note-from-account-a')).toBe(false);
  });

  it('still forgets a deletion the server has acknowledged', () => {
    useTombstoneStore.getState().markDeleted('note-synced-delete');
    useTombstoneStore.getState().clearIds(['note-synced-delete']);

    clearLocalUserData();

    // Preserving intent must not mean keeping tombstones forever: once the server has the
    // deletion, holding one would block legitimately re-creating that id.
    expect(useTombstoneStore.getState().isDeleted('note-synced-delete')).toBe(false);
  });
});
