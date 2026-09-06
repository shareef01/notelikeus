import { useAuthListener } from '@/hooks/useAuth';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';

export type CloudSyncStatus = 'unknown' | 'online' | 'offline';

/**
 * Read-only connectivity status for the settings screen.
 * Reflects network reachability rather than falsely claiming full note synchronization.
 */
export function useCloudSync() {
  const { userId, user, isGuest } = useAuthListener();
  const online = useOnlineStatus();

  const status: CloudSyncStatus = !userId ? 'unknown' : online ? 'online' : 'offline';

  return {
    userId,
    userEmail: user?.email ?? null,
    isGoogleAccount: Boolean(userId),
    isGuest,
    status,
    online,
  };
}
