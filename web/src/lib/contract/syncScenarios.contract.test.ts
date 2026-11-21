import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SupabaseNotePayload } from '@/lib/supabase/supabaseNoteMapper';

/**
 * `contracts/sync/scenarios.json` on the web engine: the end states Kotlin and Rust must reach too.
 *
 * Only the network edge is fake. The server below answers the RPCs with the database's rules (one
 * revision sequence, a base-revision check on every write, tombstones), and everything above it is
 * the app's own code: saveNote and removeNote for what the user does offline, the real
 * pullIncrementalChanges for a download, and startNotesRealtimeSync, which flushes queued edits,
 * reads the snapshot and finishes queued deletes, for a full sync.
 */

const USER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

let online = true;
let seq = 0;
const rows = new Map<string, SupabaseNotePayload>();
const tombstones = new Map<string, { revision: number; deleted_at: number }>();

type Args = Record<string, unknown>;
const offline = () => ({ data: null, error: new TypeError('Failed to fetch') });

function rowFrom(args: Args, revision: number): SupabaseNotePayload {
  return {
    type: 'note',
    note_id: args.p_note_id as string,
    local_id: args.p_local_id as number,
    revision,
    title: args.p_title as string,
    content: args.p_content as string,
    client_timestamp: args.p_client_timestamp as number,
    color: args.p_color as number,
    is_pinned: args.p_is_pinned as boolean,
    is_archived: args.p_is_archived as boolean,
    is_trashed: args.p_is_trashed as boolean,
    position: args.p_position as number,
    reminder_timestamp: (args.p_reminder_timestamp as number | null) ?? null,
    server_updated_at: 1_000_000 + revision,
    labels: args.p_labels as SupabaseNotePayload['labels'],
    checklist: args.p_checklist as SupabaseNotePayload['checklist'],
  } as SupabaseNotePayload;
}

const server = {
  apply_note_change(args: Args) {
    const id = args.p_note_id as string;
    if (tombstones.has(id)) return { status: 'conflict', error: 'note_deleted' };
    const row = rows.get(id);
    const base = args.p_base_revision as number | null;
    if (row && base !== row.revision) return { status: 'conflict', current: row };
    if (!row && base != null) return { status: 'conflict', error: 'note_not_found' };
    const stored = rowFrom(args, ++seq);
    rows.set(id, stored);
    return { status: 'applied', revision: seq, server_updated_at: stored.server_updated_at };
  },
  apply_note_delete(args: Args) {
    const id = args.p_note_id as string;
    const row = rows.get(id);
    if (!row) return tombstones.has(id) ? { status: 'applied', idempotent: true } : { status: 'conflict', error: 'note_not_found' };
    if (args.p_base_revision == null || args.p_base_revision !== row.revision) return { status: 'conflict', current: row };
    rows.delete(id);
    tombstones.set(id, { revision: ++seq, deleted_at: 9_000 });
    return { status: 'applied', revision: seq, deleted_at: 9_000 };
  },
  pull_changes(args: Args) {
    const after = args.p_after_revision as number;
    const changes = [
      ...[...rows.values()].filter((r) => (r.revision ?? 0) > after),
      ...[...tombstones].filter(([, t]) => t.revision > after).map(([note_id, t]) => ({ type: 'tombstone', note_id, ...t })),
    ].sort((a, b) => (a.revision ?? 0) - (b.revision ?? 0));
    return { changes, has_more: false, wipe_generation: 0 };
  },
  fetch_full_snapshot() {
    return {
      notes: [...rows.values()],
      tombstones: [...tombstones].map(([note_id, t]) => ({ type: 'tombstone', note_id, ...t })),
      note_count: rows.size,
      wipe_generation: 0,
    };
  },
};

const rpc = vi.fn(async (name: string, args: Args = {}) => {
  if (!online) return offline();
  // A database without the paged snapshot: the client's own fallback reads fetch_full_snapshot.
  if (name === 'fetch_snapshot_page') return { data: null, error: { code: 'PGRST202', message: 'not found' } };
  const handler = (server as Record<string, (a: Args) => unknown>)[name];
  return { data: handler ? handler(args) : [], error: null };
});

vi.mock('@/lib/supabase/client', async () => {
  const { rpcWithHeaders } = await import('@/test/rpcWithHeaders');
  return {
    getSupabaseClient: () => ({
      rpc: rpcWithHeaders(rpc),
      auth: { getSession: async () => ({ data: { session: { user: { id: USER }, access_token: 't' } }, error: null }) },
    }),
    isSupabaseBackendEnabled: () => true,
  };
});
vi.mock('@/lib/supabase/ownerSession', () => ({ ownerAccessToken: vi.fn(async () => 't') }));
vi.mock('@/lib/supabase/supabaseRealtimeSync', () => ({ subscribeSupabaseNoteRealtime: vi.fn(() => () => {}) }));

import { readContractFixture } from '@/lib/contract/contractFixtures';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNote } from '@/lib/local/notesLocalRepository';
import { removeNote, saveNote } from '@/lib/notes/noteActions';
import {
  startNotesRealtimeSync,
  stopNotesRealtimeSync,
  waitForRealtimeMirrorWriteForTests,
} from '@/lib/notes/notesSyncService';
import { resetRemoteNotesDataSourceForTests, setRemoteNotesDataSourceForTests } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { saveRevisionState } from '@/lib/supabase/revisionStore';
import { supabaseNoteToNote, noteToSupabaseRpcArgs } from '@/lib/supabase/supabaseNoteMapper';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { pullIncrementalChanges } from '@/lib/supabase/supabaseSyncEngine';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { labelFromName } from '@/types/label';
import { createEmptyNote, type Note } from '@/types/note';

interface NoteSpec {
  title: string;
  content: string;
  labels?: string[];
  checklist?: Array<{ text: string; isChecked: boolean }>;
}
interface ChangeSpec {
  note?: number;
  delete?: boolean;
  edit?: string;
  labels?: string[];
  check?: string;
  create?: { title: string; content: string };
}
type Change = null | 'delete' | ChangeSpec | ChangeSpec[];
interface Scenarios {
  synced: NoteSpec[];
  orders: string[];
  cases: Array<{
    name: string;
    synced?: NoteSpec[];
    local: Change;
    elsewhere: Change;
    expected: { device: string[]; cloud: string[] };
  }>;
}

const fixture = readContractFixture<Scenarios>('sync/scenarios.json');
const changes = (change: Change): ChangeSpec[] =>
  change == null ? [] : change === 'delete' ? [{ delete: true }] : Array.isArray(change) ? change : [change];

interface Labelled {
  title?: string;
  content?: string;
  labels?: Array<{ name: string }>;
  checklist?: Array<{ text: string; isChecked: boolean; position: number }>;
}
/** A note as the fixture writes it: `title: content #label… [x] item…`. */
const label = (n: Labelled) =>
  `${n.title ?? ''}: ${n.content ?? ''}` +
  (n.labels ?? []).map((l) => l.name).sort().map((name) => ` #${name}`).join('') +
  [...(n.checklist ?? [])].sort((a, b) => a.position - b.position).map((i) => ` ${i.isChecked ? '[x]' : '[ ]'} ${i.text}`).join('');
const cloudState = () => [...rows.values()].map((row) => label(row as unknown as Labelled)).sort();

async function deviceState(): Promise<{ stored: string[]; shown: string[] }> {
  const isDeleted = useTombstoneStore.getState().isDeleted;
  const stored = (await listNotes(USER)).filter((n) => !isDeleted(n.id)).map(label).sort();
  const shown = useNotesStore.getState().notes.filter((n) => !isDeleted(n.id)).map(label).sort();
  return { stored, shown };
}

/** The device and the cloud agree on these notes (ids "1", "2", …), as after a completed sync. */
async function seedSynced(specs: NoteSpec[]): Promise<void> {
  const synced = specs.map((spec, index) => {
    const id = String(index + 1);
    const note: Note = {
      ...createEmptyNote({ id, localId: index + 1, title: spec.title, content: spec.content }),
      labels: (spec.labels ?? []).map((name) => labelFromName(name)),
      checklist: (spec.checklist ?? []).map((item, position) => ({ id: `${id}-${position}`, position, ...item })),
      timestamp: 50,
    };
    server.apply_note_change(noteToSupabaseRpcArgs(note, null, 0) as unknown as Args);
    return supabaseNoteToNote(rows.get(id)!);
  });
  for (const note of synced) await putNote(USER, note);
  useNotesStore.getState().setNotes(synced);
  await saveRevisionState(USER, {
    noteRevisions: Object.fromEntries(synced.map((n) => [n.id, rows.get(n.id)!.revision!])),
    lastRemoteRevision: seq,
    knownCloudIds: synced.map((n) => n.id),
  });
}

async function actOffline(change: Change): Promise<void> {
  online = false;
  try {
    for (const [k, spec] of changes(change).entries()) {
      if (spec.create) {
        await saveNote({ ...createEmptyNote({ id: String(100 + k), localId: 100 + k, ...spec.create }), timestamp: 250 });
        continue;
      }
      const id = String(spec.note ?? 1);
      if (spec.delete) {
        // Offline, the cloud half fails; the local delete and its tombstone stand, which is the point.
        await removeNote(id).catch(() => {});
        continue;
      }
      const current = useNotesStore.getState().notes.find((n) => n.id === id)!;
      await saveNote({
        ...current,
        content: spec.edit ?? current.content,
        labels: spec.labels ? spec.labels.map((name) => labelFromName(name)) : current.labels,
        checklist: current.checklist.map((item) => (item.text === spec.check ? { ...item, isChecked: true } : item)),
        timestamp: current.timestamp + 100,
      });
    }
  } finally {
    online = true;
  }
}

/** Another device writes through the server. */
function actElsewhere(change: Change): void {
  for (const spec of changes(change)) {
    const id = String(spec.note ?? 1);
    const row = rows.get(id)!;
    if (spec.delete) {
      rows.delete(id);
      tombstones.set(id, { revision: ++seq, deleted_at: 8_000 });
      continue;
    }
    const checklist = (row.checklist ?? []) as Array<{ text: string; isChecked: boolean; position: number }>;
    rows.set(id, {
      ...row,
      content: spec.edit ?? row.content,
      labels: spec.labels ? spec.labels.map((name) => ({ name })) : row.labels,
      checklist: checklist.map((item) => (item.text === spec.check ? { ...item, isChecked: true } : item)),
      client_timestamp: (row.client_timestamp ?? 0) + 1,
      revision: ++seq,
      server_updated_at: 1_000_000 + seq,
    } as SupabaseNotePayload);
  }
}

async function fullSync(): Promise<void> {
  startNotesRealtimeSync(USER);
  await vi.waitFor(() => expect(useNotesStore.getState().status).toBe('ready'), { timeout: 5_000 });
  await waitForRealtimeMirrorWriteForTests();
}

describe('contracts/sync/scenarios.json on the web engine', () => {
  beforeEach(async () => {
    online = true;
    seq = 0;
    rows.clear();
    tombstones.clear();
    useTombstoneStore.getState().reset();
    useNotesStore.getState().setNotes([]);
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    setRemoteNotesDataSourceForTests(supabaseRemoteNotesDataSource);
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
  });

  afterEach(() => {
    stopNotesRealtimeSync();
    resetRemoteNotesDataSourceForTests();
    useAuthStore.getState().setUser(null);
  });

  for (const scenario of fixture.cases) {
    for (const order of fixture.orders) {
      it(`${scenario.name} [${order}]`, async () => {
        await seedSynced(scenario.synced ?? fixture.synced);
        await actOffline(scenario.local);
        actElsewhere(scenario.elsewhere);

        if (order === 'pull-first') {
          // A download before the queued work is pushed (a realtime wake-up does exactly this).
          await pullIncrementalChanges(USER, new Map());
        }
        await fullSync();

        // The snapshot is applied and queued cloud deletes finish asynchronously: wait for the end state,
        // then hold still and check again, so a state that is only briefly right cannot pass.
        const settled = async () => {
          expect(cloudState()).toEqual(scenario.expected.cloud);
          const device = await deviceState();
          expect(device.stored).toEqual(scenario.expected.device);
          expect(device.shown).toEqual(scenario.expected.device);
        };
        await vi.waitFor(settled, { timeout: 5_000 });
        await new Promise((resolve) => setTimeout(resolve, 250));
        await settled();
      });
    }
  }
});
