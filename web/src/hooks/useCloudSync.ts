import { useAuthListener } from '@/hooks/useAuth';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { selectSyncPhase, useSyncStore, type SyncPhase } from '@/store/syncStore';
import { useEffect } from 'react';

export type CloudSyncStatus = SyncPhase;

/**
 * Read-only sync status for the settings screen and editor chrome.
 * Never maps `navigator.onLine` alone to "synced".
 */
export function formatSyncPhase(phase: SyncPhase): string {
  switch (phase) {
    case 'synced':
      return 'Synced';
    case 'syncing':
      return 'Syncing…';
    case 'offline-with-local-changes':
      return 'Offline — saved locally';
    case 'error':
      return 'Sync error';
    default:
      return 'Local only';
  }
}

export function useCloudSync() {
  const { userId, user, isGuest } = useAuthListener();
  const online = useOnlineStatus();
  const sync = useSyncStore();

  useEffect(() => {
    useSyncStore.getState().markOnline(online);
  }, [online]);

  const phase = userId || isGuest ? selectSyncPhase(sync) : 'idle';
  const status: CloudSyncStatus = !userId && !isGuest ? 'idle' : phase;

  return {
    userId,
    userEmail: user?.email ?? null,
    isGoogleAccount: user?.isGoogleAccount ?? false,
    isGuest,
    status,
    statusLabel: formatSyncPhase(status),
    pendingLocalMutations: sync.pendingLocalMutations,
    lastError: sync.lastError,
    lastSuccessfulReconciliationAt: sync.lastSuccessfulReconciliationAt,
    syncedCount: sync.lastSyncedRemoteCount,
  };
}
