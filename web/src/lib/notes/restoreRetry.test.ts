import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/notes/tombstones', () => ({
  restoreCloudNote: vi.fn().mockResolvedValue(undefined),
}));

import { restoreCloudNote } from '@/lib/notes/tombstones';
import {
  collectPreservedRestoredNotes,
  retryPendingCloudRestores,
  withoutRestoredDeletes,
} from '@/lib/notes/restoreRetry';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

describe('restoreRetry', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
  });

  it('withoutRestoredDeletes drops ids that are mid-restore', () => {
    useTombstoneStore.getState().markRestored('keep');
    expect(withoutRestoredDeletes(['keep', 'drop'])).toEqual(['drop']);
  });

  it('collectPreservedRestoredNotes keeps local copies missing from the snapshot', () => {
    useTombstoneStore.getState().markRestored('keep');
    const local = createEmptyNote({ id: 'keep', localId: 1, title: 'kept' });
    const ignored = createEmptyNote({ id: 'other', localId: 2, title: 'other' });
    expect(collectPreservedRestoredNotes(new Set(['live']), [local, ignored])).toEqual([local]);
  });

  it('retryPendingCloudRestores clears the marker after a successful RPC', async () => {
    useTombstoneStore.getState().markRestored('1');
    const note = createEmptyNote({ id: '1', localId: 1, title: 'restored' });
    await retryPendingCloudRestores('user-1', [note]);
    expect(restoreCloudNote).toHaveBeenCalledWith('user-1', note);
    expect(useTombstoneStore.getState().isRestored('1')).toBe(false);
  });

  it('retryPendingCloudRestores keeps the marker when the RPC fails', async () => {
    useTombstoneStore.getState().markRestored('1');
    vi.mocked(restoreCloudNote).mockRejectedValueOnce(new Error('offline'));
    const note = createEmptyNote({ id: '1', localId: 1, title: 'restored' });
    await retryPendingCloudRestores('user-1', [note]);
    expect(useTombstoneStore.getState().isRestored('1')).toBe(true);
  });
});
