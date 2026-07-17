import { useAuthListener } from '@/hooks/useAuth';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { deleteNote, syncNotesWithCloud, uploadAllNotes } from '@/lib/firestore/notesRepository';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';
import { useToastStore } from '@/store/toastStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { useCallback, useEffect, useState } from 'react';

export type CloudSyncStatus = 'unknown' | 'ready' | 'syncing' | 'synced' | 'offline' | 'error';

export function useCloudSync() {
  const { userId, user } = useAuthListener();
  const online = useOnlineStatus();
  const notes = useNotesStore((s) => s.notes);
  const cloudAutoSyncEnabled = useSettingsStore((s) => s.cloudAutoSyncEnabled);
  const [status, setStatus] = useState<CloudSyncStatus>(userId ? 'ready' : 'unknown');
  const [syncedCount, setSyncedCount] = useState(0);

  useEffect(() => {
    if (!online) {
      setStatus('offline');
      return;
    }
    if (userId && status === 'offline') {
      setStatus('ready');
    }
  }, [online, userId, status]);

  const syncNow = useCallback(async () => {
    if (!userId) {
      useToastStore.getState().show('Sign in with Google to sync');
      return;
    }
    if (!online) {
      setStatus('offline');
      useToastStore.getState().show('You are offline', 'error');
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
  }, [userId, notes, online]);

  const restoreFromCloud = useCallback(async () => {
    if (!userId) {
      useToastStore.getState().show('Sign in with Google to restore');
      return;
    }
    setStatus('syncing');
    try {
      const { changes, merged, remoteIds } = await syncNotesWithCloud(userId, notes);
      // Same tombstone guard as the background merge in notesSyncService.ts — otherwise
      // a note deleted on this device can be resurrected by a stale cloud copy that
      // hasn't been cleaned up yet, and this path bypassed that check entirely before.
      const isDeleted = useTombstoneStore.getState().isDeleted;
      const filtered = merged.filter((note) => !isDeleted(note.id));
      const staleIds = remoteIds.filter(isDeleted);
      if (staleIds.length > 0) {
        void Promise.all(staleIds.map((id) => deleteNote(userId, id)));
      }
      useNotesStore.getState().setNotes(filtered);
      setSyncedCount(filtered.filter((n) => !n.isLocked).length);
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
  }, [userId, notes, online]);

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
