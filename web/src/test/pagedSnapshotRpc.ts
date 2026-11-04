/**
 * For tests written against `fetch_full_snapshot`: answers `fetch_snapshot_page` (AUD-008) as one page
 * cut from whatever [rpc] returns for the full snapshot, and passes every other call through.
 *
 * The full snapshot's payload is turned into pages faithfully, so a test's edge case (a SQL null, a
 * missing note_count, an empty cloud) reaches the client exactly as it would through the paged read,
 * and the test's own rpc mock still records a `fetch_full_snapshot` call for each snapshot read.
 */
type Rpc = (name: string, args?: Record<string, unknown>) => unknown;

interface FullSnapshot {
  notes?: Array<{ revision?: number }>;
  tombstones?: Array<{ revision?: number }>;
  note_count?: number;
  wipe_generation?: number;
}

/**
 * One full-snapshot answer per snapshot read: the notes page asks for it, and the tombstones page that
 * follows reuses it, so each read still counts as one fetch_full_snapshot call in the test's mock.
 * Kept per mock rather than per wrapper, because a test's client factory may build a new wrapper for
 * every getSupabaseClient() call.
 */
const lastAnswers = new WeakMap<Rpc, { data?: unknown; error?: unknown } | undefined>();

export function pagedSnapshotRpc(rpc: Rpc): Rpc {
  return async (name, args) => {
    if (name !== 'fetch_snapshot_page') return rpc(name, args);
    if (args?.p_kind !== 'tombstones' || !lastAnswers.has(rpc)) {
      lastAnswers.set(rpc, (await rpc('fetch_full_snapshot')) as { data?: unknown; error?: unknown } | undefined);
    }
    const answer = lastAnswers.get(rpc);
    if (answer?.error) return answer;
    const full = answer?.data as FullSnapshot | null | undefined;
    if (full == null) return { data: null, error: null };
    const kind = args?.p_kind === 'tombstones' ? 'tombstones' : 'notes';
    const notes = full.notes ?? [];
    const tombstones = full.tombstones ?? [];
    const revisions = [...notes, ...tombstones].map((row) => row.revision ?? 0);
    return {
      data: {
        items: kind === 'notes' ? notes : tombstones,
        next_after: null,
        state: {
          head_revision: revisions.length ? Math.max(...revisions) : 0,
          wipe_generation: full.wipe_generation ?? 0,
          note_count: full.note_count,
          tombstone_count: tombstones.length,
        },
      },
      error: null,
    };
  };
}
