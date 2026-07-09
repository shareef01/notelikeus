import { useEffect, useRef } from 'react';
import { mergeCloudNotesOnce, resetCloudMergeState } from '@/lib/notes/notesSyncService';
import { useAuthStore, selectUserId } from '@/store/authStore';

/** Runs a single cloud merge when the user signs in. No Firestore listener. */
export function useNotesSync(enabled: boolean) {
  const userId = useAuthStore(selectUserId);
  const isAuthReady = useAuthStore((state) => state.isReady);
  const lastMergedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || !isAuthReady) return;

    if (!userId) {
      lastMergedRef.current = null;
      resetCloudMergeState();
      return;
    }

    if (lastMergedRef.current === userId) return;
    lastMergedRef.current = userId;
    void mergeCloudNotesOnce(userId);
  }, [enabled, isAuthReady, userId]);
}
