import { putNotes } from '@/lib/local/notesLocalRepository';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';

/** Notes deleted on this device must never come back from a stale/racy cloud copy.
 * Splits remote notes into what's safe to show and what to purge from the cloud. */
function partitionTombstoned(remoteNotes: Note[]): { live: Note[]; staleIds: string[] } {
  const isDeleted = useTombstoneStore.getState().isDeleted;
  const live: Note[] = [];
  const staleIds: string[] = [];
  for (const note of remoteNotes) {
    if (isDeleted(note.id)) staleIds.push(note.id);
    else live.push(note);
  }
  return { live, staleIds };
}

function purgeStaleCloudDocs(userId: string, staleIds: string[]): void {
  if (staleIds.length === 0) return;
  // Fire-and-forget on purpose: the tombstone already keeps these notes out of the UI, and the
  // next snapshot retries the purge. Logging is all that stops a permanently failing delete
  // (rules change, revoked access) from being invisible.
  void Promise.all(staleIds.map((id) => getRemoteNotesDataSource().deleteNote(userId, id))).catch((error: unknown) => {
    console.warn('[Notelikeus] Purging tombstoned cloud notes failed:', error);
  });
}

let unsubscribeRealtime: (() => void) | null = null;
let realtimeUserId: string | null = null;
/** Import holds snapshots so a stale listener cannot wipe notes that have not been uploaded yet. */
let realtimeApplyPaused = false;

/**
 * Drop incoming snapshots until {@link resumeRealtimeSnapshots}. The handler is synchronous,
 * so a callback cannot be paused mid-apply.
 */
export function pauseRealtimeSnapshots(): void {
  realtimeApplyPaused = true;
}

export function resumeRealtimeSnapshots(): void {
  realtimeApplyPaused = false;
}

function applyNotes(userId: string, incoming: Note[]) {
  const current = useNotesStore.getState().notes;
  if (notesContentEqual(current, incoming)) {
    if (useNotesStore.getState().status !== 'ready') {
      useNotesStore.getState().setStatus('ready');
    }
    void putNotes(userId, incoming).catch((error: unknown) => {
      console.warn('[Notelikeus] IndexedDB mirror write failed:', error);
    });
    return;
  }
  useNotesStore.getState().setNotes(incoming);
  void putNotes(userId, incoming).catch((error: unknown) => {
    console.warn('[Notelikeus] IndexedDB mirror write failed:', error);
  });
}

/** Whether the in-memory store still holds notes that have not been deleted on this device. */
function storeHoldsLiveNotes(): boolean {
  const isDeleted = useTombstoneStore.getState().isDeleted;
  return useNotesStore.getState().notes.some((note) => !isDeleted(note.id));
}

let reconcileInFlight: Promise<void> | null = null;
let lastReconcileStartedAt = 0;
let lastSnapshotAppliedAt = 0;

const RECONCILE_MIN_INTERVAL_MS = 30_000;
const SNAPSHOT_FRESHNESS_WINDOW_MS = 15_000;

/** Cloud note IDs known from the last successful sync — drives delete-on-absence detection. */
let knownRemoteIds = new Set<string>();

/** Replaces the known-remote-id set with the complete cloud ID set from the latest sync result. */
function trackRemoteIds(ids: string[]) {
  knownRemoteIds = new Set(ids);
}

/**
 * Safety net for the always-on write path in noteActions.ts. A save made while offline is
 * stored in IndexedDB and pushed on reconnect. Re-running the revision-aware merge on
 * reconnect/tab-refocus catches a newer remote edit instead of leaving a stale local flush
 * as the last writer.
 */
async function reconcileNow(userId: string): Promise<void> {
  const now = Date.now();
  if (reconcileInFlight) return reconcileInFlight;
  if (now - lastReconcileStartedAt < RECONCILE_MIN_INTERVAL_MS) {
    return Promise.resolve();
  }
  if (now - lastSnapshotAppliedAt < SNAPSHOT_FRESHNESS_WINDOW_MS) {
    return Promise.resolve();
  }

  lastReconcileStartedAt = now;
  reconcileInFlight = (async () => {
    try {
      const localNotes = useNotesStore.getState().notes;
      const result = await getRemoteNotesDataSource().syncNotesWithCloud(
        userId,
        localNotes,
        knownRemoteIds,
      );
      // Signed out, or switched to a different account, while this was in flight — applying a
      // stale result now would repopulate a store that clearLocalUserData() already cleared.
      if (reconcileUserId !== userId) return;
      trackRemoteIds(result.remoteIds);
      const isDeleted = useTombstoneStore.getState().isDeleted;
      applyNotes(userId, result.merged.filter((note) => !isDeleted(note.id)));
    } catch (error) {
      if (reconcileUserId !== userId) return;
      lastReconcileStartedAt = 0; // allow immediate retry on the next trigger
      useNotesStore.getState().setError(
        error instanceof Error ? error.message : 'Reconcile failed',
      );
    } finally {
      reconcileInFlight = null;
    }
  })();
  return reconcileInFlight;
}

let reconcileUserId: string | null = null;
let onVisibilityChange: (() => void) | null = null;
let onOnline: (() => void) | null = null;

function attachReconciliationTriggers(userId: string) {
  reconcileUserId = userId;
  if (onVisibilityChange) return;
  onVisibilityChange = () => {
    if (document.visibilityState === 'visible' && reconcileUserId) {
      void reconcileNow(reconcileUserId);
    }
  };
  onOnline = () => {
    if (reconcileUserId) void reconcileNow(reconcileUserId);
  };
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('online', onOnline);
}

function detachReconciliationTriggers() {
  if (onVisibilityChange) document.removeEventListener('visibilitychange', onVisibilityChange);
  if (onOnline) window.removeEventListener('online', onOnline);
  onVisibilityChange = null;
  onOnline = null;
  reconcileUserId = null;
}

/**
 * Remote snapshots update IndexedDB first, then the in-memory UI store.
 * IndexedDB is the web client's durable local database; Supabase is the remote backend.
 */
export function startNotesRealtimeSync(userId: string): void {
  if (realtimeUserId === userId && unsubscribeRealtime) {
    attachReconciliationTriggers(userId);
    return;
  }

  stopNotesRealtimeSync();
  realtimeUserId = userId;
  attachReconciliationTriggers(userId);

  const remote = getRemoteNotesDataSource();
  unsubscribeRealtime = remote.subscribeToNotes(
    userId,
    (remoteNotes) => {
      if (realtimeApplyPaused) return;
      const { live, staleIds } = partitionTombstoned(remoteNotes);
      purgeStaleCloudDocs(userId, staleIds);

      // Detect notes deleted on another device: any ID we previously knew about that is absent
      // from the current snapshot (and not already tombstoned) was deleted elsewhere.
      // Guard against an empty snapshot from a transient condition — a legitimate empty set
      // after a known-populated set would mass-delete everything. The id set is only updated
      // inside the guard: an unexplained empty snapshot is not evidence of anything, and
      // clearing the set here would leave the *next* snapshot with nothing to compare against,
      // so notes deleted elsewhere would go undetected for the rest of the session.
      if (remoteNotes.length > 0 || knownRemoteIds.size === 0) {
        const currentIds = new Set(remoteNotes.map((note) => note.id));
        const isDeleted = useTombstoneStore.getState().isDeleted;
        for (const id of knownRemoteIds) {
          if (!currentIds.has(id) && !isDeleted(id)) {
            useTombstoneStore.getState().markDeleted(id);
          }
        }
        trackRemoteIds(remoteNotes.map((note) => note.id));
      }

      lastSnapshotAppliedAt = Date.now();

      // Same rule as the delete-on-absence guard above, for the notes themselves: an empty
      // snapshot must not replace a library this device is still holding. That happens for real
      // during a Firebase→Supabase migration, where local notes exist under the Supabase owner id
      // before the first upload. Blanking the store would also empty what reconcileNow() reads as
      // "local notes", so the pending notes would never be uploaded. Notes already tombstoned do
      // not count as held — deleting the last note must still leave an empty library.
      if (live.length === 0 && storeHoldsLiveNotes()) return;

      applyNotes(userId, live);
    },
    (error) => {
      useNotesStore.getState().setError(error.message);
    },
  );
}

export function stopNotesRealtimeSync(): void {
  unsubscribeRealtime?.();
  unsubscribeRealtime = null;
  realtimeUserId = null;
  lastReconcileStartedAt = 0;
  lastSnapshotAppliedAt = 0;
  knownRemoteIds = new Set();
  realtimeApplyPaused = false;
  detachReconciliationTriggers();
}
