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
 *
 * The two halves fail differently and are reported differently. A local read that throws is
 * blocking — there is nothing to show. A cloud step that throws is not: the local notes loaded a
 * moment ago are still on screen and still editable, so it becomes the non-blocking banner and the
 * next successful sync clears it.
 */
export async function retryNotesLoad(): Promise<void> {
  const store = useNotesStore.getState();
  store.clearError();
  store.clearSyncError();
  store.setStatus('loading');

  const ownerId = resolveOwnerId();
  const signedIn = Boolean(useAuthStore.getState().user);

  try {
    if (!ownerId) {
      store.setStatus('ready');
      return;
    }

    await loadLocalNotesIntoStore(ownerId);
  } catch (error: unknown) {
    console.error('[Notelikeus] Notes load retry failed:', error);
    useNotesStore
      .getState()
      .setError(formatUnknownError(error, 'Could not load notes. Please try again.'));
    return;
  }

  if (signedIn && ownerId) {
    try {
      stopNotesRealtimeSync();
      await hydrateIndexedDbFromRemote(ownerId);
      startNotesRealtimeSync(ownerId);
    } catch (error: unknown) {
      console.warn('[Notelikeus] Notes cloud sync retry failed:', error);
      useNotesStore
        .getState()
        .setSyncError(formatUnknownError(error, 'Could not sync notes. Please try again.'));
      // Realtime is still worth attaching: it is the path that recovers on its own once the
      // connection comes back, and it cannot make the already-loaded local notes any worse.
      startNotesRealtimeSync(ownerId);
    }
  }

  if (useNotesStore.getState().status === 'loading') {
    useNotesStore.getState().setStatus('ready');
  }
}
