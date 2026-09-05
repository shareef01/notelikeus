import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const rpc = vi.fn();
const realtimeHandlers: Array<() => void> = [];

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
      ch.on = (_e: string, _c: unknown, handler: () => void) => {
        realtimeHandlers.push(handler);
        return ch;
      };
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
import { startNotesRealtimeSync, stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { SUPABASE_PULL_DEBOUNCE_MS } from '@/lib/supabase/constants';
import { saveRevisionState } from '@/lib/supabase/revisionStore';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';
const CLOUD_IDS = ['n1', 'n2', 'n3', 'n4', 'n5'];

function localNote(id: string): Note {
  return createEmptyNote({
    id,
    localId: Number(id.slice(1)),
    title: 'Note ' + id,
    timestamp: 1,
  });
}

function noteRow(id: string, revision: number) {
  return {
    type: 'note',
    note_id: id,
    local_id: Number(id.slice(1)),
    title: 'Note ' + id,
    content: '',
    timestamp: 1,
    revision,
  };
}

function fullSnapshot() {
  return {
    data: {
      notes: CLOUD_IDS.map((id, index) => noteRow(id, index + 1)),
      tombstones: [],
      note_count: CLOUD_IDS.length,
    },
    error: null,
  };
}

async function settle(ms = 30) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function fireRealtimeWake() {
  realtimeHandlers.forEach((handler) => handler());
  await settle(SUPABASE_PULL_DEBOUNCE_MS + 80);
}

/**
 * `subscribeToNotes` emits what its consumer treats as the complete cloud note set:
 * notesSyncService tombstones every previously-known id missing from it, and those tombstones are
 * pushed to the cloud and to every other device. `pull_changes` is a *delta* keyed on a persisted
 * revision cursor, so it may only be applied on top of a full snapshot. A transient
 * `fetch_full_snapshot` failure used to leave the subscription with an empty map that the next
 * realtime wake filled with just the changed note — emitting a one-note "library".
 */
describe('partial remote snapshot must never be emitted as the full library', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    realtimeHandlers.length = 0;
    stopNotesRealtimeSync();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
  });

  it('re-fetches the snapshot instead of emitting a delta after a failed bootstrap', async () => {
    await saveRevisionState(USER, { lastRemoteRevision: 5, noteRevisions: {} });

    let snapshotCalls = 0;
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') {
        snapshotCalls += 1;
        if (snapshotCalls === 1) {
          return Promise.resolve({ data: null, error: new Error('network blip') });
        }
        return Promise.resolve(fullSnapshot());
      }
      if (fn === 'pull_changes') {
        return Promise.resolve({
          data: { changes: [noteRow('n3', 6)], has_more: false },
          error: null,
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(
      USER,
      (notes) => emitted.push(notes.map((note) => note.id)),
      () => {},
    );

    await settle();
    expect(emitted).toEqual([]); // a failed bootstrap emits nothing at all

    await fireRealtimeWake();
    stop();

    // The wake must recover through a fresh snapshot, never through a delta-only map.
    expect(emitted).toHaveLength(1);
    expect([...emitted[0]].sort()).toEqual([...CLOUD_IDS].sort());
  });

  it('does not tombstone the library when a wake follows a failed bootstrap', async () => {
    await saveRevisionState(USER, { lastRemoteRevision: 5, noteRevisions: {} });
    useNotesStore.getState().setNotes(CLOUD_IDS.map((id) => localNote(id)));

    let snapshotCalls = 0;
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') {
        snapshotCalls += 1;
        if (snapshotCalls === 1) {
          return Promise.resolve({ data: null, error: new Error('network blip') });
        }
        return Promise.resolve(fullSnapshot());
      }
      if (fn === 'pull_changes') {
        return Promise.resolve({
          data: { changes: [noteRow('n3', 6)], has_more: false },
          error: null,
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    startNotesRealtimeSync(USER);
    await settle();

    // Tab refocus reconciles successfully and records all five ids as known-in-cloud.
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.waitFor(() => {
      expect(useNotesStore.getState().notes).toHaveLength(CLOUD_IDS.length);
    });

    // Another device edits n3.
    await fireRealtimeWake();
    stopNotesRealtimeSync();

    expect(Object.keys(useTombstoneStore.getState().deletedAtById)).toEqual([]);
    expect(useNotesStore.getState().notes.map((note) => note.id).sort()).toEqual(
      [...CLOUD_IDS].sort(),
    );
  });

  it('applies deltas normally once a baseline snapshot has landed', async () => {
    rpc.mockImplementation((fn: string) => {
      if (fn === 'fetch_full_snapshot') return Promise.resolve(fullSnapshot());
      if (fn === 'pull_changes') {
        return Promise.resolve({
          data: { changes: [noteRow('n6', 6)], has_more: false },
          error: null,
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(
      USER,
      (notes) => emitted.push(notes.map((note) => note.id)),
      () => {},
    );

    await settle();
    expect(emitted).toHaveLength(1);

    await fireRealtimeWake();
    stop();

    expect(emitted).toHaveLength(2);
    expect([...emitted[1]].sort()).toEqual([...CLOUD_IDS, 'n6'].sort());
  });

  it('does not emit a delta when a wake races an in-flight bootstrap', async () => {
    let releaseSnapshot: (() => void) | undefined;
    const snapshotGate = new Promise<void>((resolve) => {
      releaseSnapshot = resolve;
    });

    rpc.mockImplementation(async (fn: string) => {
      if (fn === 'fetch_full_snapshot') {
        await snapshotGate;
        return fullSnapshot();
      }
      if (fn === 'pull_changes') {
        return { data: { changes: [noteRow('n3', 6)], has_more: false }, error: null };
      }
      return { data: null, error: null };
    });

    const emitted: string[][] = [];
    const stop = supabaseRemoteNotesDataSource.subscribeToNotes(
      USER,
      (notes) => emitted.push(notes.map((note) => note.id)),
      () => {},
    );

    // A wake arrives while the very first snapshot is still in flight.
    realtimeHandlers.forEach((handler) => handler());
    await settle(SUPABASE_PULL_DEBOUNCE_MS + 40);
    expect(emitted).toEqual([]);

    releaseSnapshot?.();
    await settle(80);
    stop();

    // The first emission is always the complete library, never the queued delta.
    expect(emitted.length).toBeGreaterThan(0);
    expect([...emitted[0]].sort()).toEqual([...CLOUD_IDS].sort());
  });
});
