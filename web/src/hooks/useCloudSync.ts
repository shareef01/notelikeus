import { useAuthListener } from '@/hooks/useAuth';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useNotesStore } from '@/store/notesStore';

export type CloudSyncStatus = 'unknown' | 'synced' | 'offline';

/**
 * Read-only sync status for the settings screen. Note saves write locally then to Supabase
 * (see noteActions.ts); Realtime (see useNotesSync.ts) wakes an authoritative pull.
 */
export function useCloudSync() {
  const { userId, user, isGuest } = useAuthListener();
  const online = useOnlineStatus();
  const notes = useNotesStore((s) => s.notes);

  const status: CloudSyncStatus = !userId ? 'unknown' : online ? 'synced' : 'offline';

  return {
    userId,
    userEmail: user?.email ?? null,
    isGoogleAccount: Boolean(userId),
    isGuest,
    status,
    syncedCount: notes.length,
  };
}
