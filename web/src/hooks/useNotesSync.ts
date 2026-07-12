import { useEffect, useRef } from 'react';
import {
  mergeCloudNotesOnce,
  resetCloudMergeState,
  startNotesRealtimeSync,
  stopNotesRealtimeSync,
} from '@/lib/notes/notesSyncService';
import { useAuthStore, selectUserId } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useSettingsStore } from '@/store/settingsStore';

/** Cloud merge on sign-in plus a single Firestore listener when auto-sync is on. */
export function useNotesSync(enabled: boolean) {
  const userId = useAuthStore(selectUserId);
  const isAuthReady = useAuthStore((state) => state.isReady);
  const cloudAutoSyncEnabled = useSettingsStore((state) => state.cloudAutoSyncEnabled);
  const lastMergedRef = useRef<string | null>(null);
  const prevUserIdRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || !isAuthReady) return;

    // When the user signs out (userId → null), clear local notes so the
    // next account doesn't inherit the previous user's data.
    if (!userId && prevUserIdRef.current) {
      useNotesStore.getState().reset();
      resetCloudMergeState(prevUserIdRef.current);
      prevUserIdRef.current = null;
      lastMergedRef.current = null;
      return;
    }

    // When switching to a different account, reset before merging the new one.
    if (userId && prevUserIdRef.current && prevUserIdRef.current !== userId) {
      useNotesStore.getState().reset();
      resetCloudMergeState(prevUserIdRef.current);
      lastMergedRef.current = null;
    }

    prevUserIdRef.current = userId;

    if (!userId) return;

    let cancelled = false;

    const bootstrap = async () => {
      if (lastMergedRef.current !== userId) {
        await mergeCloudNotesOnce(userId);
        if (cancelled) return;
        lastMergedRef.current = userId;
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
