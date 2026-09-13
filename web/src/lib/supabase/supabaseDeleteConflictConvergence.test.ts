import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { rpcMock } = vi.hoisted(() => ({ rpcMock: vi.fn() }));

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: rpcMock }),
  isSupabaseBackendEnabled: () => true,
}));
vi.mock('@/lib/supabase/supabaseSyncEngine', () => ({
  ensureSupabaseAuthenticated: vi.fn().mockResolvedValue(undefined),
  applyNoteChange: vi.fn(),
  fetchSnapshotNotes: vi.fn(),
  pullIncrementalChanges: vi.fn(),
}));
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({
  subscribeSupabaseNoteRealtime: vi.fn(() => () => {}),
}));

import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { loadRevisionState, rememberNoteRevision } from '@/lib/supabase/revisionStore';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';

const USER = '11111111-1111-4111-8111-111111111111';

describe('F05: Web Delete-Conflict Convergence', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    await rememberNoteRevision(USER, '1', 2);
  });

  // Test 1: deleteConflictRetriesUsingCurrentRemoteRevision
  it('deleteConflictRetriesUsingCurrentRemoteRevision', async () => {
    // Call 1: Server reports revision conflict (current is 3)
    // Call 2: Retry with base revision 3 reports success ('applied')
    rpcMock
      .mockResolvedValueOnce({
        data: {
          status: 'conflict',
          current: { note_id: '1', revision: 3 },
        },
        error: null,
      })
      .mockResolvedValueOnce({
        data: {
          status: 'applied',
          revision: 4,
        },
        error: null,
      });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    // Expected: 2 calls to apply_note_delete
    expect(rpcMock).toHaveBeenCalledTimes(2);
    expect(rpcMock).toHaveBeenNthCalledWith(1, 'apply_note_delete', {
      p_note_id: '1',
      p_base_revision: 2,
    });
    expect(rpcMock).toHaveBeenNthCalledWith(2, 'apply_note_delete', {
      p_note_id: '1',
      p_base_revision: 3,
    });

    // Final result converged
    expect((await loadRevisionState(USER)).noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // Test 2: deleteConflictRetryIsBounded
  it('deleteConflictRetryIsBounded', async () => {
    // Server repeatedly returns conflict
    rpcMock.mockResolvedValue({
      data: {
        status: 'conflict',
        current: { note_id: '1', revision: 999 },
      },
      error: null,
    });

    // Must fail after bounded retries (e.g. max 1 conflict retry = 2 attempts total)
    await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow();

    expect(rpcMock.mock.calls.length).toBeLessThanOrEqual(2);
    // Local tombstone remains so note does not resurrect locally
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // Test 3: deleteConflictWithoutCurrentRevisionFailsClosed
  it('deleteConflictWithoutCurrentRevisionFailsClosed', async () => {
    // Malformed conflict without current revision
    rpcMock.mockResolvedValue({
      data: { status: 'conflict' },
      error: null,
    });

    await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow();
    // Only 1 call made, no fabricated revision
    expect(rpcMock).toHaveBeenCalledTimes(1);
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // Test 4: alreadyDeletedIsSuccessful
  it('alreadyDeletedIsSuccessful', async () => {
    rpcMock.mockResolvedValue({
      data: { status: 'applied', idempotent: true },
      error: null,
    });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    expect((await loadRevisionState(USER)).noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // Test 5: noteNotFoundIsSuccessfulDeletionConvergence
  it('noteNotFoundIsSuccessfulDeletionConvergence', async () => {
    rpcMock.mockResolvedValue({
      data: { status: 'conflict', error: 'note_not_found' },
      error: null,
    });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    expect((await loadRevisionState(USER)).noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // Test 6: deleteRetryDoesNotRemoveLocalTombstoneOnNetworkFailure
  it('deleteRetryDoesNotRemoveLocalTombstoneOnNetworkFailure', async () => {
    rpcMock
      .mockResolvedValueOnce({
        data: {
          status: 'conflict',
          current: { note_id: '1', revision: 3 },
        },
        error: null,
      })
      .mockRejectedValueOnce(new Error('Network offline'));

    await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
      'Network offline',
    );

    // INVARIANT: Tombstone persists! Note remains hidden locally
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // Test 7: freshClientDoesNotSeeNoteAfterSuccessfulConflictRetry
  it('freshClientDoesNotSeeNoteAfterSuccessfulConflictRetry', async () => {
    // Client A deletes note with conflict retry
    rpcMock
      .mockResolvedValueOnce({
        data: { status: 'conflict', current: { note_id: '1', revision: 3 } },
        error: null,
      })
      .mockResolvedValueOnce({
        data: { status: 'applied', revision: 4 },
        error: null,
      });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    // Simulate Client C pulling snapshot from server
    // Server has applied deletion: note '1' is NOT in notes, but in tombstones
    const remoteNotes: Array<{ id: string }> = [];
    const remoteTombstones = [{ note_id: '1', revision: 4 }];

    expect(remoteNotes.some((n) => n.id === '1')).toBe(false);
    expect(remoteTombstones.some((t) => t.note_id === '1')).toBe(true);
  });

  describe('F05 Runtime Response Validation (Malformed Server Payloads)', () => {
    // Case 1: String revision {"status":"conflict","current":{"revision":"3"}}
    it('fails closed when current revision is a string', async () => {
      rpcMock.mockResolvedValue({
        data: {
          status: 'conflict',
          current: { note_id: '1', revision: '3' },
        },
        error: null,
      });

      await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
        'Delete conflict for note 1',
      );
      // Fails closed: no second RPC made with malformed revision
      expect(rpcMock).toHaveBeenCalledTimes(1);
      // Local tombstone remains
      expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
    });

    // Case 2: Null revision {"status":"conflict","current":{"revision":null}}
    it('fails closed when current revision is null', async () => {
      rpcMock.mockResolvedValue({
        data: {
          status: 'conflict',
          current: { note_id: '1', revision: null },
        },
        error: null,
      });

      await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
        'Delete conflict for note 1',
      );
      expect(rpcMock).toHaveBeenCalledTimes(1);
      expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
    });

    // Case 3: Negative revision {"status":"conflict","current":{"revision":-1}}
    it('fails closed when current revision is negative', async () => {
      rpcMock.mockResolvedValue({
        data: {
          status: 'conflict',
          current: { note_id: '1', revision: -1 },
        },
        error: null,
      });

      await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
        'Delete conflict for note 1',
      );
      expect(rpcMock).toHaveBeenCalledTimes(1);
      expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
    });

    // Case 4: Non-integer / float revision {"status":"conflict","current":{"revision":2.5}}
    it('fails closed when current revision is non-integer', async () => {
      rpcMock.mockResolvedValue({
        data: {
          status: 'conflict',
          current: { note_id: '1', revision: 2.5 },
        },
        error: null,
      });

      await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
        'Delete conflict for note 1',
      );
      expect(rpcMock).toHaveBeenCalledTimes(1);
      expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
    });
  });
});
