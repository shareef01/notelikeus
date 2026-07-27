import { deleteNote, subscribeToNotes, syncNotesWithCloud } from '@/lib/firestore/notesRepository';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';
import type { Unsubscribe } from 'firebase/firestore';

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
  void Promise.all(staleIds.map((id) => deleteNote(userId, id)));
}

let unsubscribeRealtime: Unsubscribe | null = null;
let realtimeUserId: string | null = null;

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

let reconcileInFlight: Promise<void> | null = null;

/**
 * Safety net for the always-on write path in noteActions.ts. A save made while offline is
 * queued by Firestore's own offline persistence (lib/firebase.ts) and flushed unconditionally
 * once reconnected — `setDoc` doesn't compare against whatever changed on the server in the
 * meantime, so if another device wrote a genuinely newer edit to the same note while this one
 * was offline, that flush can silently overwrite it. Re-running the serverUpdatedAt-aware merge
 * (see notesRepository.ts) on reconnect/tab-refocus catches and repairs that instead of leaving
 * it wrong until the note happens to be edited again.
 */
async function reconcileNow(userId: string): Promise<void> {
  if (reconcileInFlight) return reconcileInFlight;
  reconcileInFlight = (async () => {
    try {
      const localNotes = useNotesStore.getState().notes;
      const { merged } = await syncNotesWithCloud(userId, localNotes);
      // Signed out, or switched to a different account, while this was in flight — applying a
      // stale result now would repopulate a store that clearLocalUserData() already cleared.
      if (reconcileUserId !== userId) return;
      const isDeleted = useTombstoneStore.getState().isDeleted;
      applyNotes(merged.filter((note) => !isDeleted(note.id)));
    } catch (error) {
      if (reconcileUserId !== userId) return;
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
 * The single Firestore listener for this user's notes — module-level, never per-component.
 * Firestore is the source of truth: every snapshot fully replaces local state rather than
 * merging against it, matching the "sync automatically" design (no separate offline store to
 * reconcile against). Firestore's own persistent local cache (see lib/firebase.ts) still serves
 * the last snapshot instantly and queues writes while genuinely offline; `reconcileNow` above is
 * the backstop for when that queued write turns out to have been stale by the time it flushed.
 */
export function startNotesRealtimeSync(userId: string): void {
  if (realtimeUserId === userId && unsubscribeRealtime) {
    attachReconciliationTriggers(userId);
    return;
  }

  stopNotesRealtimeSync();
  realtimeUserId = userId;
  attachReconciliationTriggers(userId);

  unsubscribeRealtime = subscribeToNotes(
    userId,
    (remoteNotes) => {
      const { live, staleIds } = partitionTombstoned(remoteNotes);
      purgeStaleCloudDocs(userId, staleIds);
      applyNotes(live);
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
  detachReconciliationTriggers();
}
