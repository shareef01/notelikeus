import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const rpc = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  isSupabaseBackendEnabled: () => true,
  loadSupabaseUrl: () => 'http://localhost:54321',
  loadSupabaseAnonKey: () => 'anon',
  getSupabaseClient: () => ({
    rpc,
    auth: {
      getSession: () => Promise.resolve({ data: { session: { user: { id: 'u' } } }, error: null }),
    },
    channel: () => {
      const ch: Record<string, unknown> = {};
      ch.on = () => ch;
      ch.subscribe = (cb: (s: string) => void) => {
        cb('SUBSCRIBED');
        return ch;
      };
      return ch;
    },
    removeChannel: vi.fn(),
  }),
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { saveRevisionState } from '@/lib/supabase/revisionStore';
import { applyNoteChange } from '@/lib/supabase/supabaseSyncEngine';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import {
  CloudWipedElsewhereError,
  pushesBlockedByWipe,
  readWipeGeneration,
  recordWipeGeneration,
  reuploadLocalNotesAfterWipe,
  wipeGenerationForPush,
} from '@/lib/supabase/wipeGeneration';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '22222222-2222-4222-8222-222222222222';

function localNote(id: string): Note {
  return createEmptyNote({ id, localId: Number(id.slice(1)), title: `Note ${id}`, timestamp: 1 });
}

function snapshot(options: { notes?: unknown[]; wipeGeneration?: number }) {
  const notes = options.notes ?? [];
  return {
    data: {
      notes,
      tombstones: [],
      note_count: notes.length,
      wipe_generation: options.wipeGeneration ?? 0,
    },
    error: null,
  };
}

/** What PostgREST answers when the wipe-generation trigger refuses a write. */
function staleGenerationError() {
  return {
    code: 'NL002',
    details: null,
    hint: null,
    message:
      'account_wipe_generation_stale: this device last synced before this account’s cloud data ' +
      'was deleted from another device. Reopen Notelikeus to re-sync before editing.',
  };
}

/**
 * A stale device must not be able to put a deliberately deleted account's notes back, and no part
 * of the fix may be automatic: the server refuses, this browser stops pushing, the local notes
 * survive, and only the user can decide to upload them again.
 * See supabase/migrations/20261018000000_account_wipe_generation.sql.
 */
describe('wipe generation protocol', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
    rpc.mockResolvedValue({ data: {}, error: null });
  });

  it('presents the generation it last learned on every push', async () => {
    await recordWipeGeneration(USER, 4);
    rpc.mockResolvedValueOnce({ data: { status: 'applied', revision: 7 }, error: null });

    expect(await wipeGenerationForPush(USER)).toBe(4);
    await applyNoteChange(USER, localNote('1'), null);

    expect(rpc).toHaveBeenCalledWith(
      'apply_note_change',
      expect.objectContaining({ p_wipe_generation: 4 }),
    );
  });

  it('presents 0 for an account that has never been wiped', async () => {
    rpc.mockResolvedValueOnce({ data: { status: 'applied', revision: 7 }, error: null });

    await applyNoteChange(USER, localNote('1'), null);

    expect(rpc).toHaveBeenCalledWith(
      'apply_note_change',
      expect.objectContaining({ p_wipe_generation: 0 }),
    );
  });

  it('stops pushing after the server refuses a stale push, and does not retry', async () => {
    rpc.mockResolvedValueOnce({ data: null, error: staleGenerationError() });

    await expect(applyNoteChange(USER, localNote('1'), null)).rejects.toBeInstanceOf(
      CloudWipedElsewhereError,
    );

    expect(await pushesBlockedByWipe(USER)).toBe(true);
    const callsAfterRefusal = rpc.mock.calls.length;

    // The retry that matters: the next push must not even be attempted, because uploading is the
    // exact reversal the wipe generation exists to stop.
    await expect(applyNoteChange(USER, localNote('1'), null)).rejects.toBeInstanceOf(
      CloudWipedElsewhereError,
    );
    expect(rpc.mock.calls.length).toBe(callsAfterRefusal);
  });

  it('never lowers a generation it has already learned', async () => {
    await recordWipeGeneration(USER, 5);
    await recordWipeGeneration(USER, 2);

    expect(await readWipeGeneration(USER)).toBe(5);
  });

  it('holds local notes, uploads nothing and deletes nothing on a generation bump', async () => {
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') return Promise.resolve(snapshot({ wipeGeneration: 1 }));
      return Promise.resolve({ data: {}, error: null });
    });

    const result = await supabaseRemoteNotesDataSource.syncNotesWithCloud(
      USER,
      [localNote('n1'), localNote('n2')],
      new Set(['n1', 'n2']),
    );

    expect(result.merged.map((note) => note.id).sort()).toEqual(['n1', 'n2']);
    expect(useTombstoneStore.getState().isDeleted('n1')).toBe(false);
    expect(useTombstoneStore.getState().isDeleted('n2')).toBe(false);
    expect(rpc).not.toHaveBeenCalledWith('apply_note_change', expect.anything());
    expect(useNotesStore.getState().cloudWipeNotice).toEqual({
      generation: 1,
      localNoteCount: 2,
    });
    expect(await pushesBlockedByWipe(USER)).toBe(true);
  });

  it('uploads the notes the user chose to upload again, instead of deleting them', async () => {
    await recordWipeGeneration(USER, 1);
    await saveRevisionState(USER, { knownCloudIds: ['n1', 'n2'], noteRevisions: { n1: 5, n2: 6 } });
    await reuploadLocalNotesAfterWipe(USER, 1);

    expect(await pushesBlockedByWipe(USER)).toBe(false);

    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') return Promise.resolve(snapshot({ wipeGeneration: 1 }));
      if (fn === 'apply_note_change') {
        return Promise.resolve({ data: { status: 'applied', revision: 9 }, error: null });
      }
      return Promise.resolve({ data: {}, error: null });
    });

    // The stale known-id set is passed on purpose: the caller's in-memory copy is not cleared by
    // the user's choice, and reading it as "deleted elsewhere" here is what would delete the notes.
    const result = await supabaseRemoteNotesDataSource.syncNotesWithCloud(
      USER,
      [localNote('n1'), localNote('n2')],
      new Set(['n1', 'n2']),
    );

    expect(result.merged.map((note) => note.id).sort()).toEqual(['n1', 'n2']);
    expect(useTombstoneStore.getState().isDeleted('n1')).toBe(false);
    expect(rpc.mock.calls.filter(([fn]) => fn === 'apply_note_change')).toHaveLength(2);
    expect(rpc).toHaveBeenCalledWith(
      'apply_note_change',
      expect.objectContaining({ p_base_revision: null, p_wipe_generation: 1 }),
    );
  });

  it('keeps the ordinary empty-cloud guard for an account that was never wiped', async () => {
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') return Promise.resolve(snapshot({}));
      return Promise.resolve({ data: {}, error: null });
    });

    await expect(
      supabaseRemoteNotesDataSource.syncNotesWithCloud(
        USER,
        [localNote('n1')],
        new Set(['n1']),
      ),
    ).rejects.toThrow(/refusing to delete local copies/);
  });
});
