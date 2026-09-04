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

/**
 * `apply_note_delete` answers an already-tombstoned note with
 * `{status: 'applied', idempotent: true}` — the client used to look for `idempotent` inside a
 * `status === 'conflict'` branch, which the server never sends, so the stale revision was never
 * cleaned up. Same family as the rehearsal script expecting `"ok"` where the RPC returns
 * `"applied"`.
 */
describe('supabaseRemoteNotesDataSource.deleteNote — RPC status contract', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    await rememberNoteRevision(USER, '1', 10_005);
  });

  it('clears the stale revision when the server reports an idempotent delete', async () => {
    rpcMock.mockResolvedValue({ data: { status: 'applied', idempotent: true }, error: null });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    expect((await loadRevisionState(USER)).noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  it('clears the stale revision when the server has no note and no tombstone', async () => {
    rpcMock.mockResolvedValue({
      data: { status: 'conflict', error: 'note_not_found' },
      error: null,
    });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    expect((await loadRevisionState(USER)).noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  it('still surfaces a genuine revision conflict', async () => {
    rpcMock.mockResolvedValue({
      data: { status: 'conflict', current: { note_id: '1', revision: 10_009 } },
      error: null,
    });

    await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
      /Delete conflict/,
    );
  });

  it('advances the cursor on an applied delete', async () => {
    rpcMock.mockResolvedValue({
      data: { status: 'applied', revision: 10_011 },
      error: null,
    });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    const state = await loadRevisionState(USER);
    expect(state.lastRemoteRevision).toBe(10_011);
    expect(state.noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });
});
