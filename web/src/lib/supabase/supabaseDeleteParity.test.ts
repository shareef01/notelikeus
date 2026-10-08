import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { rememberNoteRevision } from '@/lib/supabase/revisionStore';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';

const USER = '11111111-1111-4111-8111-111111111111';

const { rpcMock } = vi.hoisted(() => ({ rpcMock: vi.fn() }));

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: rpcMock }),
  isSupabaseBackendEnabled: () => true,
}));

const { remoteMock } = vi.hoisted(() => ({
  remoteMock: {
    subscribeToNotes: vi.fn(),
    deleteNote: vi.fn(),
  }
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMock,
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

describe('F05: Web Delete-Conflict Safety (Fail-Before)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  // PATH 1: Snapshot updates store to 11 before deleteNote reads it.
  it('PATH 1: snapshot advancement does not authorize stale delete', async () => {
    // 1. User deletes rev 10 offline
    useTombstoneStore.getState().markDeleted('1', 10);
    
    // 2. Client reconnects, remote snapshot arrives with live rev 11
    const { startNotesRealtimeSync, stopNotesRealtimeSync, waitForRealtimeMirrorWriteForTests } = await import('@/lib/notes/notesSyncService');
    
    let emit: ((notes: import('@/types/note').Note[]) => void) | undefined;
    remoteMock.subscribeToNotes.mockImplementation((_uid: string, onData: any) => {
      emit = onData;
      return () => {};
    });

    // We don't want the actual database operation to run, just spy on the deleteNote call
    remoteMock.deleteNote.mockResolvedValue(undefined);

    startNotesRealtimeSync(USER);
    // Remote snapshot contains the note with rev 11. It's newer than the baseline (10).
    // The strict check should see that 11 > 10, drop the tombstone, and keep the note live.
    // IT MUST NOT call deleteNote for this stale tombstone.
    await rememberNoteRevision(USER, '1', 11);
    emit?.([{ id: '1', localId: 1, title: 'newer remote note', timestamp: 100, serverUpdatedAt: 100, revision: 11 }] as any);
    await waitForRealtimeMirrorWriteForTests();

    // Verify deleteNote was NOT called
    expect(remoteMock.deleteNote).not.toHaveBeenCalled();
    // And the local tombstone is dropped because it lost to the cloud
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(false);

    stopNotesRealtimeSync();
  });

  // PATH 2: Conflict escalation adopts newer remote revision.
  it('PATH 2: conflict strictly fails without escalation', async () => {
    await rememberNoteRevision(USER, '1', 10);
    useTombstoneStore.getState().markDeleted('1', 10);

    // Server returns conflict with revision 11
    rpcMock.mockResolvedValueOnce({
      data: { status: 'conflict', current: { note_id: '1', revision: 11 } },
      error: null,
    });

    await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1', 10)).rejects.toThrow(
      /Delete conflict/,
    );

    // Expected correct behavior: exactly one call with the exact baseline
    expect(rpcMock).toHaveBeenCalledTimes(1);
    expect(rpcMock).toHaveBeenCalledWith('apply_note_delete', {
      p_note_id: '1',
      p_base_revision: 10,
    });
  });

  // MISSING BASELINE: fallback queries and adopts server revision.
  it('MISSING BASELINE: strictly fails closed without remote deletion', async () => {
    useTombstoneStore.getState().markDeleted('1');
    // Store has no baseline for '1'.

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1', undefined);

    // Expected correct behavior: apply_note_delete is never called because baseline is missing
    expect(rpcMock).not.toHaveBeenCalled();
    // The tombstone remains in the store (failed closed)
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });
});
