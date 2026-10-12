import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseNotePayload } from '@/lib/supabase/supabaseNoteMapper';

/**
 * F-03 / F-04: the web client's per-note revisions and pull cursor.
 *
 * The real engine, revision store and IndexedDB code run; only the Supabase client is faked, with
 * a tiny cloud that answers the RPCs these paths call.
 */
type RpcHandler = (args: Record<string, unknown>) => unknown;
const handlers: Record<string, RpcHandler> = {};
const rpcCalls: Array<{ name: string; args: Record<string, unknown> }> = [];

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    rpc: async (name: string, args: Record<string, unknown> = {}) => {
      rpcCalls.push({ name, args });
      const handler = handlers[name];
      if (!handler) return { data: null, error: new Error(`unexpected rpc ${name}`) };
      return { data: handler(args), error: null };
    },
    auth: { getSession: async () => ({ data: { session: { user: { id: USER } } }, error: null }) },
  }),
  isSupabaseBackendEnabled: () => true,
}));
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({
  subscribeSupabaseNoteRealtime: vi.fn(() => () => {}),
}));

import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { applyRemotePageAtomically, listNotes } from '@/lib/local/notesLocalRepository';
import {
  loadRevisionState,
  rememberNoteRevision,
  saveRevisionState,
} from '@/lib/supabase/revisionStore';
import { applyNoteChange, pullIncrementalChanges } from '@/lib/supabase/supabaseSyncEngine';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

function row(id: string, title: string, revision: number): SupabaseNotePayload {
  return {
    type: 'note',
    note_id: id,
    local_id: Number(id),
    revision,
    title,
    content: 'body',
    client_timestamp: 10,
    server_updated_at: 1_000 + revision,
  };
}

function note(id: string, title: string): Note {
  return { ...createEmptyNote({ id, localId: Number(id), title }), content: 'body', timestamp: 10 };
}

beforeEach(async () => {
  for (const key of Object.keys(handlers)) delete handlers[key];
  rpcCalls.length = 0;
  useTombstoneStore.getState().reset();
  await resetNotesDatabaseForTests();
  indexedDB.deleteDatabase(NOTES_DB_NAME);
  await resetNotesDatabaseForTests();
});

describe('F-03 revision state is updated atomically', () => {
  it('keeps both revisions when two notes are recorded at once', async () => {
    await Promise.all([
      rememberNoteRevision(USER, '1', 11),
      rememberNoteRevision(USER, '2', 12),
      rememberNoteRevision(USER, '3', 13),
    ]);

    expect((await loadRevisionState(USER)).noteRevisions).toEqual({ '1': 11, '2': 12, '3': 13 });
  });

  it('keeps a push\'s revision when a pull page computed before it lands after it', async () => {
    await saveRevisionState(USER, { noteRevisions: { '1': 5 }, lastRemoteRevision: 10 });
    const stale = await loadRevisionState(USER); // a pull read this before its round trip
    await rememberNoteRevision(USER, '1', 14); // the push lands meanwhile

    await applyRemotePageAtomically({
      ownerId: USER,
      upserts: [note('2', 'from another device')],
      deletedNoteIds: [],
      noteRevisions: { ...stale.noteRevisions, '2': 12 },
      lastRemoteRevision: 12,
    });

    expect((await loadRevisionState(USER)).noteRevisions).toEqual({ '1': 14, '2': 12 });
  });

  it('keeps a revision learned after a snapshot was taken when the snapshot is saved', async () => {
    await saveRevisionState(USER, { noteRevisions: { '1': 5, gone: 4 }, lastRemoteRevision: 5 });
    await rememberNoteRevision(USER, '1', 14); // a push that landed after the snapshot below

    await saveRevisionState(USER, { noteRevisions: { '1': 9 }, lastRemoteRevision: 10 });

    expect((await loadRevisionState(USER)).noteRevisions).toEqual({ '1': 14 });
  });
});

describe('F-04 the pull cursor only moves with pulls and snapshots', () => {
  it('does not move the cursor past a revision it has not pulled', async () => {
    await saveRevisionState(USER, { noteRevisions: { '1': 5 }, lastRemoteRevision: 10 });

    await rememberNoteRevision(USER, '1', 12);

    expect((await loadRevisionState(USER)).lastRemoteRevision).toBe(10);
  });

  it('still pulls another device\'s change committed between the cursor and this device\'s push', async () => {
    await saveRevisionState(USER, { noteRevisions: { '1': 5 }, lastRemoteRevision: 10 });
    handlers.apply_note_change = () => ({ status: 'applied', revision: 12, server_updated_at: 2_000 });
    handlers.pull_changes = (args) =>
      (args.p_after_revision as number) < 11
        ? { changes: [row('2', 'written on android', 11), row('1', 'mine', 12)], has_more: false }
        : { changes: [], has_more: false };

    await applyNoteChange(USER, note('1', 'mine'), 5);
    const byId = new Map<string, Note>();
    await pullIncrementalChanges(USER, byId);

    expect(byId.get('2')?.title).toBe('written on android');
    expect((await listNotes(USER)).map((n) => n.id).sort()).toEqual(['1', '2']);
    expect((await loadRevisionState(USER)).lastRemoteRevision).toBe(12);
  });
});
