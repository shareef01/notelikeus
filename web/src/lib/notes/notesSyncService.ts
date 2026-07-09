import { subscribeToNotes, syncNotesWithCloud } from '@/lib/firestore/notesRepository';
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

function applyNotes(incoming: Note[]) {
  const current = useNotesStore.getState().notes;
  if (notesContentEqual(current, incoming)) {
    if (useNotesStore.getState().status !== 'ready') {
      useNotesStore.getState().setStatus('ready');
    }
    return;
  }
  useNotesStore.getState().setNotes(incoming);
}

function applyRemoteSnapshot(localNotes: Note[], remoteNotes: Note[]): Note[] {
  const remoteIds = new Set(remoteNotes.map((note) => note.id));
  const isInitial = knownCloudIds.size === 0;
  const result = new Map<string, Note>();

  for (const remote of remoteNotes) {
    const local = localNotes.find((note) => note.id === remote.id);
    if (!local || local.isLocked) {
      result.set(remote.id, remote);
    } else if (local.timestamp > remote.timestamp) {
      result.set(remote.id, local);
    } else {
      result.set(remote.id, remote);
    }
  }

  for (const local of localNotes) {
    if (result.has(local.id)) continue;
    if (local.isLocked) {
      result.set(local.id, local);
      continue;
    }
    if (!isInitial && knownCloudIds.has(local.id) && !remoteIds.has(local.id)) {
      continue;
    }
    result.set(local.id, local);
  }

  knownCloudIds = remoteIds;
  return Array.from(result.values());
}

function attachVisibilityRefresh(userId: string) {
  if (visibilityHandler) return;
  visibilityHandler = () => {
    if (document.visibilityState !== 'visible' || realtimeUserId !== userId) return;
    void mergeCloudNotesOnce(userId);
  };
  document.addEventListener('visibilitychange', visibilityHandler);
}

function detachVisibilityRefresh() {
  if (!visibilityHandler) return;
  document.removeEventListener('visibilitychange', visibilityHandler);
  visibilityHandler = null;
}

/** One-time cloud merge when the user signs in. No live listener (avoids render storms). */
export function mergeCloudNotesOnce(userId: string): Promise<void> {
  if (mergedForUserId === userId && !mergeInFlight) {
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
      const { merged, remoteIds } = await syncNotesWithCloud(userId, localNotes);
      applyNotes(merged);
      knownCloudIds = new Set(remoteIds);
      mergedForUserId = userId;
    } catch (error) {
      useNotesStore.getState().setError(
        error instanceof Error ? error.message : 'Cloud merge failed',
      );
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

  unsubscribeRealtime = subscribeToNotes(
    userId,
    (remoteNotes) => {
      const localNotes = useNotesStore.getState().notes;
      applyNotes(applyRemoteSnapshot(localNotes, remoteNotes));
    },
    (error) => {
      useNotesStore.getState().setError(error.message);
    },
  );

  attachVisibilityRefresh(userId);
}

export function stopNotesRealtimeSync(): void {
  unsubscribeRealtime?.();
  unsubscribeRealtime = null;
  realtimeUserId = null;
  detachVisibilityRefresh();
}

export function resetCloudMergeState(): void {
  mergedForUserId = null;
  mergeInFlight = null;
  knownCloudIds = new Set();
  stopNotesRealtimeSync();
}
