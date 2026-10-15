import { useAuthStore } from '@/store/authStore';
import { pendingIdsFor, useSyncStatusStore } from '@/store/syncStatusStore';

export interface SyncQueueState {
  /** Notes of the signed-in account holding an edit the cloud has not confirmed. */
  pendingIds: readonly string[];
  isFlushing: boolean;
  /** The last push left edits behind; the next sync retries them. */
  flushFailed: boolean;
}

/** The signed-in account's queue of unconfirmed edits. Empty for a guest or when signed out. */
export function useSyncQueue(): SyncQueueState {
  const uid = useAuthStore((state) => state.user?.uid ?? null);
  const pendingIds = useSyncStatusStore((state) => pendingIdsFor(state, uid));
  const isFlushing = useSyncStatusStore((state) => (uid ? (state.flushingByOwner[uid] ?? false) : false));
  const flushFailed = useSyncStatusStore((state) => (uid ? (state.failedByOwner[uid] ?? false) : false));
  return { pendingIds, isFlushing, flushFailed };
}
