import { useAuthListener } from '@/hooks/useAuth';
import { syncNotesWithCloud, uploadAllNotes } from '@/lib/firestore/notesRepository';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useToastStore } from '@/store/toastStore';
import { useCallback, useState } from 'react';

export type CloudSyncStatus = 'unknown' | 'ready' | 'syncing' | 'synced' | 'error';

export function useCloudSync() {
  const { userId, user } = useAuthListener();
  const notes = useNotesStore((s) => s.notes);
  const cloudAutoSyncEnabled = useSettingsStore((s) => s.cloudAutoSyncEnabled);
  const [status, setStatus] = useState<CloudSyncStatus>(userId ? 'ready' : 'unknown');
  const [syncedCount, setSyncedCount] = useState(0);

  const syncNow = useCallback(async () => {
    if (!userId) {
      useToastStore.getState().show('Sign in with Google to sync');
      return;
    }
    setStatus('syncing');
    try {
      const count = await uploadAllNotes(userId, notes);
      setSyncedCount(count);
      setStatus('synced');
      useToastStore.getState().show(`Synced ${count} note${count === 1 ? '' : 's'}`);
    } catch (error) {
      setStatus('error');
      useToastStore.getState().show(
        error instanceof Error ? error.message : 'Sync failed',
        'error',
      );
    }
  }, [userId, notes]);

  const restoreFromCloud = useCallback(async () => {
    if (!userId) {
      useToastStore.getState().show('Sign in with Google to restore');
      return;
    }
    setStatus('syncing');
    try {
      const { changes, merged } = await syncNotesWithCloud(userId, notes);
      useNotesStore.getState().setNotes(merged);
      setSyncedCount(merged.filter((n) => !n.isLocked).length);
      setStatus('synced');
      useToastStore.getState().show(
        changes > 0 ? `Restored ${changes} change${changes === 1 ? '' : 's'}` : 'Already up to date',
      );
    } catch (error) {
      setStatus('error');
      useToastStore.getState().show(
        error instanceof Error ? error.message : 'Restore failed',
        'error',
      );
    }
  }, [userId, notes]);

  return {
    userId,
    userEmail: user?.email ?? null,
    isGoogleAccount: Boolean(userId),
    cloudAutoSyncEnabled,
    status,
    syncedCount,
    syncNow,
    restoreFromCloud,
  };
}
