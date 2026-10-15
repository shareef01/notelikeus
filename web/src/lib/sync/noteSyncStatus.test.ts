import { describe, expect, it } from 'vitest';
import { noteSyncStatus, type NoteSyncInput } from '@/lib/sync/noteSyncStatus';

const settled: NoteSyncInput = {
  saveFailed: false,
  isSaving: false,
  isSavedLocally: true,
  isSignedIn: true,
  isOnline: true,
  isPending: false,
  isFlushing: false,
  flushFailed: false,
};

describe('noteSyncStatus', () => {
  it('is synced only when nothing about the note is waiting for the cloud', () => {
    expect(noteSyncStatus(settled)).toBe('synced');
    expect(noteSyncStatus({ ...settled, isPending: true })).toBe('waiting');
  });

  it('puts the local write first: a failed or running save outranks any sync state', () => {
    const syncing = { ...settled, isPending: true, isFlushing: true };
    expect(noteSyncStatus({ ...syncing, saveFailed: true, isSaving: true })).toBe('save-failed');
    expect(noteSyncStatus({ ...syncing, isSaving: true })).toBe('saving');
    expect(noteSyncStatus({ ...syncing, isSavedLocally: false })).toBe('unsaved');
  });

  it('never talks about sync to a guest', () => {
    expect(noteSyncStatus({ ...settled, isSignedIn: false, isPending: true, isOnline: false })).toBe(
      'on-device',
    );
  });

  it('says offline before syncing or failed, since neither can change until the network returns', () => {
    const waiting = { ...settled, isPending: true, isOnline: false };
    expect(noteSyncStatus({ ...waiting, isFlushing: true, flushFailed: true })).toBe('offline');
  });

  it('prefers a push in flight over the previous one having failed', () => {
    const waiting = { ...settled, isPending: true };
    expect(noteSyncStatus({ ...waiting, isFlushing: true, flushFailed: true })).toBe('syncing');
    expect(noteSyncStatus({ ...waiting, flushFailed: true })).toBe('sync-failed');
  });

  it('a settled note stays synced while the account has other edits failing', () => {
    expect(noteSyncStatus({ ...settled, flushFailed: true, isOnline: false })).toBe('synced');
  });
});
