import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';
import { useAuthStore } from '@/store/authStore';
import { useEffect } from 'react';

/** Boots guest-mode notes from IndexedDB when the user continues without an account. */
export function useGuestLocalNotesBootstrap(enabled: boolean) {
  const isGuest = useAuthStore((state) => state.guestMode);
  const isReady = useAuthStore((state) => state.isReady);

  useEffect(() => {
    if (!enabled || !isReady || !isGuest) return;
    const ownerId = resolveOwnerId();
    if (!ownerId) return;
    void loadLocalNotesIntoStore(ownerId).catch((error: unknown) => {
      console.error('[Notelikeus] Guest local notes bootstrap failed:', error);
    });
  }, [enabled, isGuest, isReady]);
}
