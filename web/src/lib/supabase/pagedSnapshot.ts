import { getSupabaseClient } from '@/lib/supabase/client';
import type { SupabaseNotePayload, SupabaseTombstonePayload } from '@/lib/supabase/supabaseNoteMapper';

/**
 * The full snapshot, read a page at a time (AUD-008).
 *
 * `fetch_full_snapshot` returns every note and tombstone as one response, which an account near its
 * note limit can push past a timeout or a proxy's size limit. `fetch_snapshot_page` returns them in
 * pages, and each page reports the account's state: newest revision, wipe generation, note count and
 * tombstone count. Any write that changes a snapshot's contents moves that state, so pages that all
 * report the same state are exactly the snapshot at that state. When they differ, a write landed
 * mid-read, and the pages are read again.
 *
 * The result has the same shape and order as `fetch_full_snapshot`, so everything that checks it
 * (note_count against the rows, the wipe generation, the unexplained-id guard) is unchanged.
 */
export interface PagedSnapshot {
  notes: SupabaseNotePayload[];
  tombstones: SupabaseTombstonePayload[];
  note_count: number;
  wipe_generation: number;
}

interface SnapshotState {
  head_revision: number;
  wipe_generation: number;
  note_count: number;
  tombstone_count: number;
}

interface SnapshotPage<T> {
  items?: T[];
  next_after?: string | null;
  state?: SnapshotState;
}

/** Rows per request; the server also stops a notes page at 4 MiB of text. */
export const SNAPSHOT_PAGE_LIMIT = 500;
/** Full reads before giving up while the account keeps changing under them. */
export const SNAPSHOT_ATTEMPTS = 3;
/** A safety stop against a server that never says it is done. */
const MAX_PAGES = 10_000;

export class SnapshotKeptChangingError extends Error {
  constructor() {
    super('The cloud notes kept changing while they were being read. Sync will try again.');
    this.name = 'SnapshotKeptChangingError';
  }
}

/** PostgREST's answer for an RPC this database does not have: one that predates the paged read. */
function isMissingFunction(error: unknown): boolean {
  const { code } = (error ?? {}) as { code?: unknown };
  return code === 'PGRST202';
}

function sameState(a: SnapshotState | undefined, b: SnapshotState | undefined): boolean {
  return (
    a != null &&
    b != null &&
    a.head_revision === b.head_revision &&
    a.wipe_generation === b.wipe_generation &&
    a.note_count === b.note_count &&
    a.tombstone_count === b.tombstone_count
  );
}

/** One kind's pages, in order, and the state each reported. Null when the database is older. */
async function readAll<T>(
  kind: 'notes' | 'tombstones',
  states: SnapshotState[],
): Promise<T[] | null> {
  const items: T[] = [];
  let after: string | null = null;
  for (let page = 0; page < MAX_PAGES; page++) {
    // eslint-disable-next-line no-await-in-loop -- each page continues from the last
    const { data, error } = await getSupabaseClient().rpc('fetch_snapshot_page', {
      p_kind: kind,
      p_after: after,
      p_limit: SNAPSHOT_PAGE_LIMIT,
    });
    if (error) {
      if (isMissingFunction(error)) return null;
      throw error;
    }
    // As with fetch_full_snapshot: SQL NULL is never an empty account, it is a read that failed.
    if (data == null) throw new Error('Incomplete snapshot: fetch_snapshot_page returned null');
    const result = data as SnapshotPage<T>;
    if (!Array.isArray(result.items) || result.state == null) {
      throw new Error(`Incomplete snapshot: a ${kind} page had no items or state`);
    }
    items.push(...result.items);
    states.push(result.state);
    after = result.next_after ?? null;
    if (after == null) return items;
  }
  throw new Error(`Incomplete snapshot: more than ${MAX_PAGES} ${kind} pages`);
}

/**
 * The snapshot, assembled from pages; or the database's one-response snapshot when it does not have
 * the paged read yet, so this client can be deployed before the migration.
 */
export async function fetchPagedSnapshot(): Promise<PagedSnapshot | null> {
  for (let attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
    const states: SnapshotState[] = [];
    // eslint-disable-next-line no-await-in-loop -- a retry follows a read the account changed under
    const notes = await readAll<SupabaseNotePayload>('notes', states);
    if (notes == null) return null;
    // eslint-disable-next-line no-await-in-loop -- tombstones belong to the same read as the notes
    const tombstones = await readAll<SupabaseTombstonePayload>('tombstones', states);
    if (tombstones == null) return null;
    const first = states[0];
    if (first && states.every((state) => sameState(state, first))) {
      return {
        // The order fetch_full_snapshot returns: notes by position then local id, tombstones by revision.
        notes: notes.sort(
          (a, b) => (a.position ?? 0) - (b.position ?? 0) || (a.local_id ?? 0) - (b.local_id ?? 0),
        ),
        tombstones: tombstones.sort((a, b) => (a.revision ?? 0) - (b.revision ?? 0)),
        note_count: first.note_count,
        wipe_generation: first.wipe_generation,
      };
    }
  }
  throw new SnapshotKeptChangingError();
}
