import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useTombstoneStore } from '@/store/tombstoneStore';

const USER = '11111111-1111-4111-8111-111111111111';

const { rpcMock } = vi.hoisted(() => ({ rpcMock: vi.fn() }));

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: rpcMock }),
  isSupabaseBackendEnabled: () => true,
}));

const { remoteMock } = vi.hoisted(() => ({
  remoteMock: { subscribeToNotes: vi.fn(), deleteNote: vi.fn() },
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

/**
 * Permanent-delete authority is captured where the user acts and never re-derived afterwards.
 *
 * These are deliberately discriminating: each one fails against an implementation that recaptures
 * the revision from the server after suspension, or that lets a later observation overwrite the
 * baseline the user's decision was made against.
 */
describe('F05: delete authority is bound to the action-time revision', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  // DEL-6: nothing may ask the server what the revision is now and adopt that answer as authority.
  // An implementation that looked the current revision up and used it would call the lookup RPC
  // here, which is exactly the laundering this guards against.
  it('a delete with no captured baseline never recaptures a revision from the server', async () => {
    await supabaseRemoteNotesDataSource.deleteNote(USER, '1', undefined);

    expect(rpcMock).not.toHaveBeenCalledWith('lookup_note_revision', expect.anything());
    expect(rpcMock).not.toHaveBeenCalledWith('apply_note_delete', expect.anything());
    expect(rpcMock).not.toHaveBeenCalled();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  // DEL-4: retrying or re-marking must not widen authority. The first baseline is the one the user
  // acted on; a later, newer observation must not replace it.
  it('re-marking a deleted note keeps the original baseline', async () => {
    useTombstoneStore.getState().markDeleted('1', 10);
    useTombstoneStore.getState().markDeleted('1', 12);

    expect(useTombstoneStore.getState().baselineRevisionById['1']).toBe(10);
  });

  // DEL-1/DEL-5: the revision captured at the action is the revision the server is given, with no
  // additional lookups in between.
  it('sends the captured baseline and nothing else to the server', async () => {
    rpcMock.mockResolvedValueOnce({ data: { status: 'applied', revision: 11 }, error: null });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1', 10);

    expect(rpcMock).toHaveBeenCalledTimes(1);
    expect(rpcMock).toHaveBeenCalledWith('apply_note_delete', {
      p_note_id: '1',
      p_base_revision: 10,
    });
  });
});
