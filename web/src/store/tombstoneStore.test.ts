import { beforeEach, describe, expect, it } from 'vitest';
import { TOMBSTONE_TTL_MS } from '@/lib/firestore/tombstones';
import { useTombstoneStore } from '@/store/tombstoneStore';

describe('tombstoneStore', () => {
  beforeEach(() => {
    useTombstoneStore.getState().reset();
    localStorage.clear();
  });

  it('markDeleted and isDeleted', () => {
    useTombstoneStore.getState().markDeleted('a', 1_000);
    expect(useTombstoneStore.getState().isDeleted('a')).toBe(true);
    expect(useTombstoneStore.getState().deletedAtById.a).toBe(1_000);
    expect(useTombstoneStore.getState().isDeleted('b')).toBe(false);
  });

  it('markDeleted keeps the first deletedAt', () => {
    useTombstoneStore.getState().markDeleted('a', 100);
    useTombstoneStore.getState().markDeleted('a', 999);
    expect(useTombstoneStore.getState().deletedAtById.a).toBe(100);
  });

  it('mergeFromCloud keeps the earlier deletedAt', () => {
    useTombstoneStore.getState().markDeleted('a', 500);
    useTombstoneStore.getState().mergeFromCloud({ a: 100, b: 200 });
    expect(useTombstoneStore.getState().deletedAtById).toEqual({ a: 100, b: 200 });
  });

  it('pruneExpired removes old tombstones', () => {
    useTombstoneStore.getState().markDeleted('old', 1_000);
    useTombstoneStore.getState().markDeleted('fresh', Date.now());
    const pruned = useTombstoneStore.getState().pruneExpired(1_000 + TOMBSTONE_TTL_MS);
    expect(pruned).toEqual(['old']);
    expect(useTombstoneStore.getState().isDeleted('old')).toBe(false);
    expect(useTombstoneStore.getState().isDeleted('fresh')).toBe(true);
  });

  it('clearIds and reset', () => {
    useTombstoneStore.getState().markDeleted('a');
    useTombstoneStore.getState().markDeleted('b');
    useTombstoneStore.getState().clearIds(['a']);
    expect(useTombstoneStore.getState().isDeleted('a')).toBe(false);
    expect(useTombstoneStore.getState().isDeleted('b')).toBe(true);
    useTombstoneStore.getState().reset();
    expect(useTombstoneStore.getState().deletedAtById).toEqual({});
  });
});
