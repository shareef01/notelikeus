import { beforeEach, describe, expect, it, vi } from 'vitest';

const rpc = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc }),
  isSupabaseBackendEnabled: () => true,
}));

import { fetchSnapshotNotes } from '@/lib/supabase/supabaseSyncEngine';

const row = (id: string, revision: number) => ({
  note_id: id,
  local_id: Number(id),
  title: `Note ${id}`,
  content: '',
  client_timestamp: 100,
  color: 0,
  is_pinned: false,
  is_archived: false,
  is_trashed: false,
  position: Number(id),
  revision,
  labels: [],
  checklist: [],
});

/** AUD-008: this client can run against a database that predates the paged snapshot. */
describe('snapshot read on an older database', () => {
  beforeEach(() => {
    rpc.mockReset();
  });

  it('falls back to the one-response snapshot when the paged one does not exist', async () => {
    rpc.mockImplementation(async (name: string) => {
      if (name === 'fetch_snapshot_page') {
        return { data: null, error: { code: 'PGRST202', message: 'Could not find the function' } };
      }
      if (name === 'fetch_full_snapshot') {
        return { data: { notes: [row('1', 4), row('2', 5)], tombstones: [], note_count: 2 }, error: null };
      }
      return { data: null, error: null };
    });

    const snapshot = await fetchSnapshotNotes({ hydrateAttachments: false });

    expect(snapshot.notes.map((note) => note.id)).toEqual(['1', '2']);
    expect(snapshot.maxRevision).toBe(5);
    expect(rpc.mock.calls.map((call) => call[0])).toEqual(['fetch_snapshot_page', 'fetch_full_snapshot']);
  });

  it('uses the paged read when the database has it, and never the one-response snapshot', async () => {
    rpc.mockImplementation(async (_name: string, args: { p_kind: string }) => ({
      data: {
        items: args.p_kind === 'notes' ? [row('1', 4)] : [],
        next_after: null,
        state: { head_revision: 4, wipe_generation: 0, note_count: 1, tombstone_count: 0 },
      },
      error: null,
    }));

    const snapshot = await fetchSnapshotNotes({ hydrateAttachments: false });

    expect(snapshot.notes.map((note) => note.id)).toEqual(['1']);
    expect(rpc.mock.calls.some((call) => call[0] === 'fetch_full_snapshot')).toBe(false);
  });
});
