import { useEffect, useRef } from 'react';
import { clearLocalUserData } from '@/lib/bootstrap';
import {
  loadLastMergedUserId,
  saveLastMergedUserId,
} from '@/lib/notes/lastMergedUser';
import {
  mergeCloudNotesOnce,
  resetCloudMergeState,
  startNotesRealtimeSync,
  stopNotesRealtimeSync,
} from '@/lib/notes/notesSyncService';
import { useAuthStore, selectUserId } from '@/store/authStore';
import { useSettingsStore } from '@/store/settingsStore';

/** Cloud merge on sign-in plus a single Firestore listener when auto-sync is on. */
export function useNotesSync(enabled: boolean) {
  const userId = useAuthStore(selectUserId);
  const isAuthReady = useAuthStore((state) => state.isReady);
  const cloudAutoSyncEnabled = useSettingsStore((state) => state.cloudAutoSyncEnabled);
  const lastMergedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || !isAuthReady) return;

    if (!userId) {
      lastMergedRef.current = null;
      resetCloudMergeState();
      return;
    }

    let cancelled = false;

    const bootstrap = async () => {
      // Seeded from storage so the guard survives a reload, not just this mount.
      const lastMerged = lastMergedRef.current ?? loadLastMergedUserId();
      if (lastMerged !== userId) {
        // Account switch without a clean sign-out — drop prior user's local data first.
        if (lastMerged != null) {
          clearLocalUserData();
          resetCloudMergeState();
        }
        await mergeCloudNotesOnce(userId);
        if (cancelled) return;
        lastMergedRef.current = userId;
        saveLastMergedUserId(userId);
      }

      if (cloudAutoSyncEnabled) {
        startNotesRealtimeSync(userId);
      } else {
        stopNotesRealtimeSync();
      }
    };

    void bootstrap();

    return () => {
      cancelled = true;
      stopNotesRealtimeSync();
    };
  }, [enabled, isAuthReady, userId, cloudAutoSyncEnabled]);
}
