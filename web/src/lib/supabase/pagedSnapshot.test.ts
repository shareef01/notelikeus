import { beforeEach, describe, expect, it, vi } from 'vitest';

const rpc = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc }),
}));

import {
  fetchPagedSnapshot,
  SNAPSHOT_ATTEMPTS,
  SnapshotKeptChangingError,
} from '@/lib/supabase/pagedSnapshot';

/** AUD-008: the full snapshot, read in pages that must all describe the same account state. */

const STATE = { head_revision: 9, wipe_generation: 0, note_count: 3, tombstone_count: 1 };

const note = (id: string, position: number, revision = Number(id)) => ({
  note_id: id,
  local_id: Number(id),
  position,
  revision,
});

/** A server holding [notes] and [tombstones], answering in pages of [size]. */
function server(
  notes: ReturnType<typeof note>[],
  tombstones: { note_id: string; revision: number }[],
  size: number,
  state: (call: number) => typeof STATE = () => STATE,
) {
  let call = 0;
  rpc.mockImplementation(async (_name: string, args: { p_kind: string; p_after: string | null }) => {
    const rows = (args.p_kind === 'notes' ? notes : tombstones)
      .slice()
      .sort((a, b) => a.note_id.localeCompare(b.note_id))
      .filter((row) => args.p_after == null || row.note_id > args.p_after);
    const items = rows.slice(0, size);
    const more = rows.length > size;
    return {
      data: { items, next_after: more ? items[items.length - 1]!.note_id : null, state: state(call++) },
      error: null,
    };
  });
}

beforeEach(() => {
  rpc.mockReset();
});

describe('fetchPagedSnapshot', () => {
  it('assembles every page, in the order fetch_full_snapshot returns', async () => {
    server([note('3', 0), note('1', 2), note('2', 1)], [{ note_id: '7', revision: 8 }], 1);

    const snapshot = await fetchPagedSnapshot();

    expect(snapshot?.notes.map((row) => row.note_id)).toEqual(['3', '2', '1']);
    expect(snapshot?.tombstones.map((row) => row.note_id)).toEqual(['7']);
    expect(snapshot?.note_count).toBe(3);
    expect(snapshot?.wipe_generation).toBe(0);
    const pages = rpc.mock.calls.map((call) => `${call[1].p_kind}:${call[1].p_after}`);
    expect(pages).toEqual(['notes:null', 'notes:1', 'notes:2', 'tombstones:null']);
  });

  it('reads again when a write lands between pages, and accepts the read that stayed still', async () => {
    // First read: the second page reports a newer revision. Second read: unchanged throughout.
    server([note('1', 0), note('2', 1), note('3', 2)], [], 2, (call) =>
      call === 1 ? { ...STATE, head_revision: 10 } : STATE,
    );

    const snapshot = await fetchPagedSnapshot();

    expect(snapshot?.notes).toHaveLength(3);
    expect(rpc.mock.calls.filter((call) => call[1].p_kind === 'notes' && call[1].p_after == null)).toHaveLength(2);
  });

  it('gives up rather than accept pages from different states', async () => {
    server([note('1', 0), note('2', 1)], [], 1, (call) => ({ ...STATE, head_revision: call }));

    await expect(fetchPagedSnapshot()).rejects.toBeInstanceOf(SnapshotKeptChangingError);
    expect(rpc.mock.calls.filter((call) => call[1].p_after == null && call[1].p_kind === 'notes')).toHaveLength(
      SNAPSHOT_ATTEMPTS,
    );
  });

  it.each([
    ['tombstone count', { tombstone_count: 2 }],
    ['wipe generation', { wipe_generation: 1 }],
    ['note count', { note_count: 4 }],
  ])('treats a changed %s as a different state', async (_label, change) => {
    server([note('1', 0)], [{ note_id: '5', revision: 5 }], 1, (call) =>
      call % 2 === 1 ? { ...STATE, ...change } : STATE,
    );

    await expect(fetchPagedSnapshot()).rejects.toBeInstanceOf(SnapshotKeptChangingError);
  });

  it('says the database is older, so the caller can use the one-response snapshot', async () => {
    rpc.mockResolvedValue({ data: null, error: { code: 'PGRST202', message: 'Could not find the function' } });

    await expect(fetchPagedSnapshot()).resolves.toBeNull();
  });

  it('passes any other failure through, so a failed read never looks like an empty account', async () => {
    rpc.mockResolvedValue({ data: null, error: { code: 'NL003', message: 'account_deleted: gone' } });

    await expect(fetchPagedSnapshot()).rejects.toMatchObject({ code: 'NL003' });
  });

  it('refuses a page that came back as SQL NULL or without its state', async () => {
    rpc.mockResolvedValueOnce({ data: null, error: null });
    await expect(fetchPagedSnapshot()).rejects.toThrow(/returned null/);

    rpc.mockResolvedValueOnce({ data: { items: [], next_after: null }, error: null });
    await expect(fetchPagedSnapshot()).rejects.toThrow(/no items or state/);
  });
});
