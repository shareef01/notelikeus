import { applyMergedLiveSet } from '@/lib/local/hydrateFromRemote';
import { getOwnerMeta, setOwnerMeta } from '@/lib/local/notesLocalRepository';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { reconcileLocalAndRemoteSnapshot } from '@/lib/notes/reconcileLocalAndRemoteSnapshot';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import {
  isSuspiciousEmptySnapshotError,
  sanitizeSyncErrorMessage,
} from '@/lib/remote/remoteErrors';
import { useNotesStore } from '@/store/notesStore';
import { useSyncStore } from '@/store/syncStore';
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
  void Promise.all(staleIds.map((id) => getRemoteNotesDataSource().deleteNote(userId, id))).catch(
    (error: unknown) => {
      console.warn('[Notelikeus] Purging tombstoned cloud notes failed:', error);
    },
  );
}

let unsubscribeRealtime: (() => void) | null = null;
let realtimeUserId: string | null = null;
/** Import holds snapshots so a stale listener cannot wipe notes that have not been uploaded yet. */
let realtimeApplyPaused = false;
let applyQueue: Promise<void> = Promise.resolve();

export function pauseRealtimeSnapshots(): void {
  realtimeApplyPaused = true;
}

export function resumeRealtimeSnapshots(): void {
  realtimeApplyPaused = false;
}

async function persistKnownRemoteIds(userId: string, ids: Set<string>): Promise<void> {
  await setOwnerMeta(userId, { knownRemoteIds: [...ids] });
}

async function applyReconciledSnapshot(userId: string, remoteNotes: Note[]): Promise<void> {
  const localNotes = useNotesStore.getState().notes;
  const result = reconcileLocalAndRemoteSnapshot({
    localNotes,
    remoteNotes,
    remoteTombstones: {},
    knownRemoteIds,
    isDeleted: (id) => useTombstoneStore.getState().isDeleted(id),
  });
  for (const id of result.newlyDeletedIds) {
    useTombstoneStore.getState().markDeleted(id);
  }
  trackRemoteIds([...result.nextKnownRemoteIds]);
  void persistKnownRemoteIds(userId, knownRemoteIds);
  if (!notesContentEqual(localNotes, result.merged)) {
    await applyMergedLiveSet(userId, result.merged);
  } else {
    useNotesStore.getState().setStatus('ready');
    await applyMergedLiveSet(userId, result.merged);
  }
}

function enqueueApply(userId: string, remoteNotes: Note[]): void {
  applyQueue = applyQueue
    .then(async () => {
      if (realtimeUserId !== userId) return;
      try {
        await applyReconciledSnapshot(userId, remoteNotes);
        useSyncStore.getState().markReconcileSuccess();
      } catch (error) {
        if (realtimeUserId !== userId) return;
        if (isSuspiciousEmptySnapshotError(error)) {
          useNotesStore.getState().setError(sanitizeSyncErrorMessage(error));
          useSyncStore.getState().markError(sanitizeSyncErrorMessage(error));
          return;
        }
        useNotesStore.getState().setError(sanitizeSyncErrorMessage(error));
        useSyncStore.getState().markError(sanitizeSyncErrorMessage(error));
      }
    })
    .catch((error: unknown) => {
      console.warn('[Notelikeus] Snapshot apply failed:', error);
    });
}

let knownRemoteIds = new Set<string>();

function trackRemoteIds(ids: string[]) {
  knownRemoteIds = new Set(ids);
}

let reconcileInFlight: Promise<void> | null = null;
let lastReconcileStartedAt = 0;
let lastSnapshotAppliedAt = 0;

const RECONCILE_MIN_INTERVAL_MS = 30_000;
const SNAPSHOT_FRESHNESS_WINDOW_MS = 15_000;

async function reconcileNow(userId: string, force = false): Promise<void> {
  const now = Date.now();
  if (reconcileInFlight) return reconcileInFlight;
  if (!force) {
    if (now - lastReconcileStartedAt < RECONCILE_MIN_INTERVAL_MS) {
      return Promise.resolve();
    }
    if (now - lastSnapshotAppliedAt < SNAPSHOT_FRESHNESS_WINDOW_MS) {
      return Promise.resolve();
    }
  }

  lastReconcileStartedAt = now;
  useSyncStore.getState().markSyncing('reconcile');
  reconcileInFlight = (async () => {
    try {
      const localNotes = useNotesStore.getState().notes;
      const result = await getRemoteNotesDataSource().syncNotesWithCloud(
        userId,
        localNotes,
        knownRemoteIds,
      );
      if (reconcileUserId !== userId) return;
      trackRemoteIds(result.remoteIds);
      void persistKnownRemoteIds(userId, knownRemoteIds);
      const isDeleted = useTombstoneStore.getState().isDeleted;
      const merged = result.merged.filter((note) => !isDeleted(note.id));
      await applyMergedLiveSet(userId, merged);
      lastSnapshotAppliedAt = Date.now();
      useSyncStore.getState().markReconcileSuccess();
    } catch (error) {
      if (reconcileUserId !== userId) return;
      lastReconcileStartedAt = 0;
      const message = sanitizeSyncErrorMessage(error);
      useNotesStore.getState().setError(message);
      useSyncStore.getState().markError(message);
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
  if (onOnline) document.removeEventListener('online', onOnline);
  onVisibilityChange = null;
  onOnline = null;
  reconcileUserId = null;
}

/** Force a reconcile, skipping the freshness window — used by the in-app Retry control. */
export async function retryNotesReconciliation(userId: string): Promise<void> {
  lastReconcileStartedAt = 0;
  lastSnapshotAppliedAt = 0;
  await reconcileNow(userId, true);
}

export function startNotesRealtimeSync(userId: string): void {
  if (realtimeUserId === userId && unsubscribeRealtime) {
    attachReconciliationTriggers(userId);
    return;
  }

  stopNotesRealtimeSync();
  realtimeUserId = userId;
  attachReconciliationTriggers(userId);
  void getOwnerMeta(userId).then((meta) => {
    if (realtimeUserId !== userId) return;
    if (meta?.knownRemoteIds?.length) {
      knownRemoteIds = new Set(meta.knownRemoteIds);
    }
  });

  const remote = getRemoteNotesDataSource();
  unsubscribeRealtime = remote.subscribeToNotes(
    userId,
    (remoteNotes) => {
      if (realtimeApplyPaused) return;
      const { live, staleIds } = partitionTombstoned(remoteNotes);
      purgeStaleCloudDocs(userId, staleIds);
      lastSnapshotAppliedAt = Date.now();
      enqueueApply(userId, live);
    },
    (error) => {
      const message = sanitizeSyncErrorMessage(error);
      useNotesStore.getState().setError(message);
      useSyncStore.getState().markError(message);
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
  applyQueue = Promise.resolve();
  detachReconciliationTriggers();
}
