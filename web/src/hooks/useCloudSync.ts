import { useAuthListener } from '@/hooks/useAuth';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useNotesStore } from '@/store/notesStore';

export type CloudSyncStatus = 'unknown' | 'synced' | 'offline';

/**
 * Read-only sync status for the settings screen. There is nothing to trigger here anymore —
 * every note save/delete writes straight to Firestore (see noteActions.ts) and the realtime
 * listener (see useNotesSync.ts) keeps this device's view live. `notes.length` doubles as the
 * "synced count" because, once signed in, `notes` IS the Firestore collection.
 */
export function useCloudSync() {
  const { userId, user } = useAuthListener();
  const online = useOnlineStatus();
  const notes = useNotesStore((s) => s.notes);

  const status: CloudSyncStatus = !userId ? 'unknown' : online ? 'synced' : 'offline';

  return {
    userId,
    userEmail: user?.email ?? null,
    isGoogleAccount: Boolean(userId),
    status,
    syncedCount: notes.length,
  };
}
