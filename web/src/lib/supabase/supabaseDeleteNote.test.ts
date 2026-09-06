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
import { forgetNoteRevision, loadRevisionState, rememberNoteRevision } from '@/lib/supabase/revisionStore';
import { beginNotesSyncSession } from '@/lib/supabase/syncSession';
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

  it('resolves a missing local revision from lookup_note_revision, then deletes', async () => {
    await forgetNoteRevision(USER, '1');
    rpcMock.mockImplementation(async (fn: string) => {
      if (fn === 'lookup_note_revision') {
        return { data: { exists: true, tombstoned: false, revision: 10_042 }, error: null };
      }
      return { data: { status: 'applied', revision: 10_043 }, error: null };
    });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    expect(rpcMock).toHaveBeenCalledWith('lookup_note_revision', { p_note_id: '1' });
    expect(rpcMock).toHaveBeenCalledWith('apply_note_delete', {
      p_note_id: '1',
      p_base_revision: 10_042,
    });
    expect((await loadRevisionState(USER)).noteRevisions['1']).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  it('skips apply_note_delete only after the backend confirms the note is absent', async () => {
    await forgetNoteRevision(USER, '1');
    rpcMock.mockImplementation(async (fn: string) => {
      if (fn === 'lookup_note_revision') {
        return { data: { exists: false, tombstoned: false, revision: null }, error: null };
      }
      return { data: { status: 'applied' }, error: null };
    });

    await supabaseRemoteNotesDataSource.deleteNote(USER, '1');

    expect(rpcMock).toHaveBeenCalledWith('lookup_note_revision', { p_note_id: '1' });
    expect(rpcMock).not.toHaveBeenCalledWith(
      'apply_note_delete',
      expect.objectContaining({ p_note_id: '1' }),
    );
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  it('aborts when the signed-in account changes mid-delete', async () => {
    await forgetNoteRevision(USER, '1');
    rpcMock.mockImplementation(async (fn: string) => {
      if (fn === 'lookup_note_revision') {
        beginNotesSyncSession('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
        return { data: { exists: true, tombstoned: false, revision: 10_042 }, error: null };
      }
      return { data: { status: 'applied', revision: 10_043 }, error: null };
    });

    await expect(supabaseRemoteNotesDataSource.deleteNote(USER, '1')).rejects.toThrow(
      /Account changed/,
    );
    expect(rpcMock).not.toHaveBeenCalledWith(
      'apply_note_delete',
      expect.objectContaining({ p_note_id: '1' }),
    );
  });
});
