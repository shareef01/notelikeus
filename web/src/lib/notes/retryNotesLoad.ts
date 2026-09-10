import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import { hydrateIndexedDbFromRemote, loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';
import { formatUnknownError } from '@/lib/errors/formatUnknownError';
import { startNotesRealtimeSync, stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';

/**
 * Re-attempts notes load without wiping local IndexedDB data.
 *
 * Reloads the durable local mirror first so the user keeps what is already on device,
 * then restarts remote sync when signed in.
 */
export async function retryNotesLoad(): Promise<void> {
  const store = useNotesStore.getState();
  store.clearError();
  store.setStatus('loading');

  const ownerId = resolveOwnerId();
  const signedIn = Boolean(useAuthStore.getState().user);

  try {
    if (!ownerId) {
      store.setStatus('ready');
      return;
    }

    await loadLocalNotesIntoStore(ownerId);

    if (signedIn) {
      stopNotesRealtimeSync();
      await hydrateIndexedDbFromRemote(ownerId);
      startNotesRealtimeSync(ownerId);
    }

    if (useNotesStore.getState().status === 'loading') {
      useNotesStore.getState().setStatus('ready');
    }
  } catch (error: unknown) {
    console.error('[Notelikeus] Notes load retry failed:', error);
    useNotesStore
      .getState()
      .setError(formatUnknownError(error, 'Could not load notes. Please try again.'));
  }
}
