import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';
import { formatUnknownError } from '@/lib/errors/formatUnknownError';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useEffect } from 'react';

/** Boots guest-mode notes from IndexedDB when the user continues without an account. */
export function useGuestLocalNotesBootstrap(enabled: boolean) {
  const isGuest = useAuthStore((state) => state.guestMode);
  const isReady = useAuthStore((state) => state.isReady);

  useEffect(() => {
    if (!enabled || !isReady || !isGuest) return;
    const ownerId = resolveOwnerId();
    // No namespace means there is nothing to read yet. Leaving the store in 'loading' here would
    // hang the screen on a spinner with nothing on the way to end it.
    if (!ownerId) {
      if (useNotesStore.getState().status === 'loading') {
        useNotesStore.getState().setStatus('ready');
      }
      return;
    }

    let cancelled = false;
    useNotesStore.getState().setStatus('loading');

    void loadLocalNotesIntoStore(ownerId)
      .then(() => {
        if (cancelled) return;
        // A successful read is the answer to any earlier failure, including one the user is
        // looking at right now via the Retry button.
        useNotesStore.getState().clearError();
        if (useNotesStore.getState().status === 'loading') {
          useNotesStore.getState().setStatus('ready');
        }
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.error('[Notelikeus] Guest local notes bootstrap failed:', error);
        // Blocking on purpose: in guest mode IndexedDB is the only copy that exists, so a failed
        // read is not something a later cloud sync can paper over. `setError` also moves the
        // store out of 'loading', which is what makes the failure visible instead of a spinner
        // that never ends, and `retryNotesLoad` re-runs exactly this read.
        useNotesStore
          .getState()
          .setError(formatUnknownError(error, 'Could not load notes stored on this device.'));
      });

    return () => {
      cancelled = true;
    };
  }, [enabled, isGuest, isReady]);
}
