import { create } from 'zustand';

/**
 * What this tab knows about each account's queue of local edits the cloud has not confirmed, for
 * the editor's status line and the main screen's banner (DESIGN_SYSTEM §5.1).
 *
 * Fed from two places only: the queue itself (`notesLocalRepository` publishes its pending ids
 * after every marker change) and the flush loop (`pendingPushes`), which says when a push is
 * running and whether the last one left edits behind. So "Synced" can only be shown once the
 * server has accepted a note's latest edit — never inferred from being online.
 */
interface SyncStatusState {
  /** Ids of notes holding an unconfirmed edit, per owner. */
  pendingByOwner: Record<string, readonly string[]>;
  /** Owners whose queue is being pushed right now. */
  flushingByOwner: Record<string, boolean>;
  /** Owners whose last finished flush failed to push at least one edit. */
  failedByOwner: Record<string, boolean>;
  setPending: (ownerId: string, ids: Iterable<string>) => void;
  setFlushing: (ownerId: string, flushing: boolean) => void;
  setFailed: (ownerId: string, failed: boolean) => void;
}

const NO_IDS: readonly string[] = [];

export const useSyncStatusStore = create<SyncStatusState>((set, get) => ({
  pendingByOwner: {},
  flushingByOwner: {},
  failedByOwner: {},
  setPending: (ownerId, ids) => {
    const next = [...ids].sort();
    const current = get().pendingByOwner[ownerId] ?? NO_IDS;
    if (current.length === next.length && current.every((id, i) => id === next[i])) return;
    set((state) => ({ pendingByOwner: { ...state.pendingByOwner, [ownerId]: next } }));
  },
  setFlushing: (ownerId, flushing) => {
    if ((get().flushingByOwner[ownerId] ?? false) === flushing) return;
    set((state) => ({ flushingByOwner: { ...state.flushingByOwner, [ownerId]: flushing } }));
  },
  setFailed: (ownerId, failed) => {
    if ((get().failedByOwner[ownerId] ?? false) === failed) return;
    set((state) => ({ failedByOwner: { ...state.failedByOwner, [ownerId]: failed } }));
  },
}));

export function pendingIdsFor(state: SyncStatusState, ownerId: string | null): readonly string[] {
  return ownerId ? (state.pendingByOwner[ownerId] ?? NO_IDS) : NO_IDS;
}
