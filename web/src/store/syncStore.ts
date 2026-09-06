import { create } from 'zustand';

export type SyncPhase =
  | 'idle'
  | 'syncing'
  | 'synced'
  | 'offline-with-local-changes'
  | 'error';

export type SyncOperation = 'idle' | 'reconcile' | 'upsert' | 'hydrate';

interface SyncState {
  online: boolean;
  pendingLocalMutations: boolean;
  lastSuccessfulReconciliationAt: number | null;
  lastError: string | null;
  currentOperation: SyncOperation;
  /** Notes successfully confirmed by the last reconciliation (not merely local count). */
  lastSyncedRemoteCount: number;
  markOnline: (online: boolean) => void;
  markPendingLocalMutations: (pending: boolean) => void;
  markSyncing: (operation: SyncOperation) => void;
  markReconcileSuccess: (remoteCount?: number) => void;
  markError: (message: string) => void;
  reset: () => void;
}

function derivePhase(state: {
  online: boolean;
  pendingLocalMutations: boolean;
  lastSuccessfulReconciliationAt: number | null;
  lastError: string | null;
  currentOperation: SyncOperation;
}): SyncPhase {
  if (state.currentOperation !== 'idle') return 'syncing';
  if (state.lastError) return 'error';
  if (!state.online) {
    return state.pendingLocalMutations ? 'offline-with-local-changes' : 'idle';
  }
  if (state.pendingLocalMutations) return 'syncing';
  if (state.lastSuccessfulReconciliationAt != null) return 'synced';
  return 'idle';
}

export const useSyncStore = create<SyncState>((set) => ({
  online: typeof navigator === 'undefined' ? true : navigator.onLine,
  pendingLocalMutations: false,
  lastSuccessfulReconciliationAt: null,
  lastError: null,
  currentOperation: 'idle',
  lastSyncedRemoteCount: 0,
  markOnline: (online) => set({ online }),
  markPendingLocalMutations: (pendingLocalMutations) => set({ pendingLocalMutations }),
  markSyncing: (operation) =>
    set({ currentOperation: operation, lastError: null }),
  markReconcileSuccess: (remoteCount) =>
    set((state) => ({
      currentOperation: 'idle',
      lastError: null,
      pendingLocalMutations: false,
      lastSuccessfulReconciliationAt: Date.now(),
      lastSyncedRemoteCount: remoteCount ?? state.lastSyncedRemoteCount,
    })),
  markError: (lastError) =>
    set({ currentOperation: 'idle', lastError }),
  reset: () =>
    set({
      pendingLocalMutations: false,
      lastSuccessfulReconciliationAt: null,
      lastError: null,
      currentOperation: 'idle',
      lastSyncedRemoteCount: 0,
    }),
}));

export function selectSyncPhase(state: SyncState): SyncPhase {
  return derivePhase(state);
}
