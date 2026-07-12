import { subscribeToNotes, syncNotesWithCloud } from '@/lib/firestore/notesRepository';
import {
  applyRemoteDeletions,
  clearKnownCloudIds,
  loadKnownCloudIds,
  saveKnownCloudIds,
} from '@/lib/notes/cloudSyncState';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';
import type { Note } from '@/types/note';
import type { Unsubscribe } from 'firebase/firestore';

let mergedForUserId: string | null = null;
let mergeInFlight: Promise<void> | null = null;
let unsubscribeRealtime: Unsubscribe | null = null;
let realtimeUserId: string | null = null;
let knownCloudIds = new Set<string>();
let visibilityHandler: (() => void) | null = null;

/** Cloud IDs that were just deleted locally and should not be re-added by realtime snapshots. */
const pendingLocalDeletes = new Set<string>();

/**
 * Marks a cloud ID as locally deleted so realtime snapshots won't restore it
 * before the Firestore delete propagates.
 */
export function markCloudIdsForLocalDeletion(cloudId: string): void {
  pendingLocalDeletes.add(cloudId);
}

function applyNotes(incoming: Note[]) {
  // Deduplicate by cloudId — keep the version with the latest timestamp
  const deduped = Array.from(
    new Map(incoming.map((note) => [note.cloudId || note.id, note])).values(),
  );
  const current = useNotesStore.getState().notes;
  if (notesContentEqual(current, deduped)) {
    if (useNotesStore.getState().status !== 'ready') {
      useNotesStore.getState().setStatus('ready');
    }
    return;
  }
  useNotesStore.getState().setNotes(deduped);
}

export function applyRemoteSnapshot(
  localNotes: Note[],
  remoteNotes: Note[],
  previousKnownCloudIds: Set<string>,
): Note[] {
  const remoteCloudIds = new Set(remoteNotes.map((note) => note.cloudId));
  const isInitial = previousKnownCloudIds.size === 0;
  const result = new Map<string, Note>();

  // Clean up pending deletes that are now confirmed gone from the server
  for (const cloudId of pendingLocalDeletes) {
    if (!remoteCloudIds.has(cloudId)) {
      pendingLocalDeletes.delete(cloudId);
    }
  }

  for (const remote of remoteNotes) {
    // Skip notes that were just deleted locally — the Firestore delete hasn't propagated yet
    if (pendingLocalDeletes.has(remote.cloudId)) continue;

    const local = localNotes.find(
      (note) => note.cloudId === remote.cloudId || note.id === remote.id,
    );
    if (local?.isLocked) {
      result.set(local.cloudId, local);
    } else if (!local) {
      result.set(remote.cloudId, remote);
    } else if (local.timestamp > remote.timestamp) {
      result.set(local.cloudId, local);
    } else {
      result.set(remote.cloudId, { ...remote, id: local.id, localId: local.localId });
    }
  }

  for (const local of localNotes) {
    if (result.has(local.cloudId)) continue;
    if (local.isLocked) {
      result.set(local.cloudId, local);
      continue;
    }
    if (!isInitial && previousKnownCloudIds.has(local.cloudId) && !remoteCloudIds.has(local.cloudId)) {
      continue;
    }
    result.set(local.cloudId, local);
  }

  knownCloudIds = remoteCloudIds;
  return applyRemoteDeletions(Array.from(result.values()), previousKnownCloudIds, remoteCloudIds);
}

function attachVisibilityRefresh(userId: string) {
  if (visibilityHandler) return;
  visibilityHandler = () => {
    if (document.visibilityState !== 'visible' || realtimeUserId !== userId) return;
    void mergeCloudNotesOnce(userId, true);
  };
  document.addEventListener('visibilitychange', visibilityHandler);
}

function detachVisibilityRefresh() {
  if (!visibilityHandler) return;
  document.removeEventListener('visibilitychange', visibilityHandler);
  visibilityHandler = null;
}

/** Merge local notes with cloud. When force=true, re-runs even after the first merge this session. */
export function mergeCloudNotesOnce(userId: string, force = false): Promise<void> {
  if (!force && mergedForUserId === userId && !mergeInFlight) {
    return Promise.resolve();
  }

  if (mergeInFlight) {
    return mergeInFlight;
  }

  mergeInFlight = (async () => {
    if (mergedForUserId !== userId) {
      useNotesStore.getState().setStatus('loading');
    }

    try {
      const localNotes = useNotesStore.getState().notes;
      const persistedKnown = loadKnownCloudIds(userId);
      const { merged, remoteCloudIds } = await syncNotesWithCloud(
        userId,
        localNotes,
        persistedKnown,
      );
      applyNotes(merged);
      knownCloudIds = new Set(remoteCloudIds);
      saveKnownCloudIds(userId, knownCloudIds);
      mergedForUserId = userId;
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Cloud merge failed';
      if (!message.includes('permission')) {
        useNotesStore.getState().setError(message);
      }
    } finally {
      mergeInFlight = null;
    }
  })();

  return mergeInFlight;
}

/** Single Firestore listener — module-level, never per-component. */
export function startNotesRealtimeSync(userId: string): void {
  if (realtimeUserId === userId && unsubscribeRealtime) return;

  stopNotesRealtimeSync();
  realtimeUserId = userId;
  knownCloudIds = loadKnownCloudIds(userId);

  let retryCount = 0;
  const MAX_RETRIES = 5;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;

  const start = () => {
    if (realtimeUserId !== userId) return;

    unsubscribeRealtime = subscribeToNotes(
      userId,
      (remoteNotes) => {
        retryCount = 0; // Reset on success
        useNotesStore.getState().setError(null);
        const localNotes = useNotesStore.getState().notes;
        const merged = applyRemoteSnapshot(localNotes, remoteNotes, knownCloudIds);
        applyNotes(merged);
        knownCloudIds = new Set(remoteNotes.map((note) => note.cloudId));
        saveKnownCloudIds(userId, knownCloudIds);
      },
      (error) => {
        // Don't show permissions errors as note-list errors — they're auth-related
        if (error.message.includes('permission')) {
          retryCount++;
          if (retryCount > MAX_RETRIES) {
            console.error('[Notelikeus] Sync failed after', MAX_RETRIES, 'retries:', error.message);
            useNotesStore.getState().setError(
              'Sync unavailable. Try signing out and back in, or check your connection.',
            );
            return;
          }
          const delay = Math.min(2000 * Math.pow(2, retryCount - 1), 30000);
          console.warn(
            `[Notelikeus] Sync deferred (attempt ${retryCount}/${MAX_RETRIES}), retrying in ${delay / 1000}s:`,
            error.message,
          );
          if (!retryTimer) {
            retryTimer = setTimeout(() => {
              retryTimer = null;
              stopNotesRealtimeSync();
              realtimeUserId = userId;
              knownCloudIds = loadKnownCloudIds(userId);
              start();
            }, delay);
          }
          return;
        }
        useNotesStore.getState().setError(error.message);
      },
    );
  };

  start();
  attachVisibilityRefresh(userId);
}

export function stopNotesRealtimeSync(): void {
  unsubscribeRealtime?.();
  unsubscribeRealtime = null;
  realtimeUserId = null;
  detachVisibilityRefresh();
}

export function resetCloudMergeState(userId?: string): void {
  mergedForUserId = null;
  mergeInFlight = null;
  knownCloudIds = new Set();
  pendingLocalDeletes.clear();
  if (userId) {
    clearKnownCloudIds(userId);
  }
  stopNotesRealtimeSync();
}
