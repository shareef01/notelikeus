import { deleteNote, subscribeToNotes, syncNotesWithCloud } from '@/lib/firestore/notesRepository';
import { loadKnownCloudIds, saveKnownCloudIds } from '@/lib/notes/knownCloudIds';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';
import type { Unsubscribe } from 'firebase/firestore';

/** Notes deleted on this device must never come back from a stale/racy cloud copy.
 * Splits remote notes into what's safe to merge and what to purge from the cloud. */
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
  void Promise.all(staleIds.map((id) => deleteNote(userId, id)));
}

let mergedForUserId: string | null = null;
let mergeInFlight: Promise<void> | null = null;
let unsubscribeRealtime: Unsubscribe | null = null;
let realtimeUserId: string | null = null;
let knownCloudIds = loadKnownCloudIds();
let visibilityHandler: (() => void) | null = null;

function rememberKnownCloudIds(ids: Set<string>) {
  knownCloudIds = ids;
  saveKnownCloudIds(ids);
}

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
  const isDeleted = useTombstoneStore.getState().isDeleted;
  const result = new Map<string, Note>();

  for (const remote of remoteNotes) {
    if (isDeleted(remote.id)) continue;
    const local = localNotes.find((note) => note.id === remote.id);
    if (!local) {
      result.set(remote.id, remote);
    } else if (local.isLocked) {
      // Locked notes are never uploaded (see isCloudSyncEligible), so any cloud copy
      // that still exists for this id is necessarily a stale pre-lock version — never
      // let it override local, matching mergeRemoteNotes' handling of the same case.
      result.set(remote.id, local);
    } else if (local.timestamp > remote.timestamp) {
      result.set(remote.id, local);
    } else {
      result.set(remote.id, remote);
    }
  }

  for (const local of localNotes) {
    if (result.has(local.id)) continue;
    if (isDeleted(local.id)) {
      // Match Android: keep locked locals despite a colliding tombstone id.
      if (local.isLocked) result.set(local.id, local);
      continue;
    }
    if (local.isLocked) {
      result.set(local.id, local);
      continue;
    }
    if (!isInitial && knownCloudIds.has(local.id) && !remoteIds.has(local.id)) {
      continue;
    }
    result.set(local.id, local);
  }

  rememberKnownCloudIds(remoteIds);
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
      const previouslyKnown = loadKnownCloudIds();
      knownCloudIds = previouslyKnown;
      const { merged, remoteIds } = await syncNotesWithCloud(
        userId,
        localNotes,
        previouslyKnown,
      );
      const isDeleted = useTombstoneStore.getState().isDeleted;
      applyNotes(merged.filter((note) => !isDeleted(note.id)));
      purgeStaleCloudDocs(userId, remoteIds.filter(isDeleted));
      rememberKnownCloudIds(new Set(remoteIds.filter((id) => !isDeleted(id))));
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
      const { live, staleIds } = partitionTombstoned(remoteNotes);
      purgeStaleCloudDocs(userId, staleIds);
      const localNotes = useNotesStore.getState().notes;
      applyNotes(applyRemoteSnapshot(localNotes, live));
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
  // Keep persisted IDs across auth blips; clearLocalUserData wipes storage on sign-out.
  knownCloudIds = loadKnownCloudIds();
  stopNotesRealtimeSync();
}
