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
      getSession: () =>
        Promise.resolve({ data: { session: { user: { id: 'u' } } }, error: null }),
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
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';

function localNote(id: string): Note {
  return createEmptyNote({ id, localId: Number(id.slice(1)), title: `Note ${id}`, timestamp: 1 });
}

function snapshot(options: {
  notes?: unknown[];
  tombstones?: { note_id: string; deleted_at: number }[];
}) {
  const notes = options.notes ?? [];
  return {
    data: {
      notes,
      tombstones: options.tombstones ?? [],
      note_count: notes.length,
    },
    error: null,
  };
}

/**
 * `syncNotesWithCloud` used to refuse *every* empty snapshot whenever it had previously known any
 * cloud id, while `subscribeToNotes.loadBaseline()` already subtracted the ids that tombstones
 * explain. So deleting your last note on another device — a perfectly ordinary thing to do — put
 * the reconcile path into a refusal it could not get out of, because the known-id set is only
 * rewritten at the end of a *successful* sync.
 *
 * Both paths now ask the same question, and the guard still has to fire for the case it exists
 * for: an empty answer with no tombstones behind it.
 */
describe('empty cloud snapshots during reconcile', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
  });

  it('reconciles a legitimately emptied cloud when tombstones explain every missing id', async () => {
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') {
        return Promise.resolve(
          snapshot({
            tombstones: [
              { note_id: 'n1', deleted_at: 10 },
              { note_id: 'n2', deleted_at: 11 },
            ],
          }),
        );
      }
      return Promise.resolve({ data: {}, error: null });
    });

    const result = await supabaseRemoteNotesDataSource.syncNotesWithCloud(
      USER,
      [localNote('n1'), localNote('n2')],
      new Set(['n1', 'n2']),
    );

    expect(result.merged).toEqual([]);
    expect(result.remoteIds).toEqual([]);
    expect(useTombstoneStore.getState().isDeleted('n1')).toBe(true);
  });

  it('still refuses an empty snapshot that no tombstone explains', async () => {
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') return Promise.resolve(snapshot({}));
      return Promise.resolve({ data: {}, error: null });
    });

    await expect(
      supabaseRemoteNotesDataSource.syncNotesWithCloud(
        USER,
        [localNote('n1'), localNote('n2')],
        new Set(['n1', 'n2']),
      ),
    ).rejects.toThrow(/refusing to delete local copies/);

    expect(useTombstoneStore.getState().isDeleted('n1')).toBe(false);
    expect(useTombstoneStore.getState().isDeleted('n2')).toBe(false);
  });

  it('refuses when only some of the missing ids are explained', async () => {
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') {
        return Promise.resolve(snapshot({ tombstones: [{ note_id: 'n1', deleted_at: 10 }] }));
      }
      return Promise.resolve({ data: {}, error: null });
    });

    await expect(
      supabaseRemoteNotesDataSource.syncNotesWithCloud(
        USER,
        [localNote('n1'), localNote('n2')],
        new Set(['n1', 'n2']),
      ),
    ).rejects.toThrow(/Cloud returned no notes but 1 were expected/);
  });

  it('leaves the loadBaseline guard refusing an unexplained empty snapshot', async () => {
    await saveRevisionState(USER, { knownCloudIds: ['n1'], noteRevisions: { n1: 4 } });
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') return Promise.resolve(snapshot({}));
      return Promise.resolve({ data: {}, error: null });
    });

    const errors: unknown[] = [];
    const unsubscribe = supabaseRemoteNotesDataSource.subscribeToNotes(
      USER,
      () => {},
      (error) => errors.push(error),
    );
    await vi.waitFor(() => expect(errors).toHaveLength(1));
    unsubscribe();

    expect(String((errors[0] as Error).message)).toMatch(/refusing to overwrite local copies/);
  });
});
