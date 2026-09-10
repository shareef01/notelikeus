import { useEffect, useRef } from 'react';
import { clearLocalUserDataForAccountSwitch } from '@/lib/bootstrap';
import { hydrateIndexedDbFromRemote, loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';
import { migrateLegacyLocalNotes } from '@/lib/notes/legacyLocalMigration';
import {
  loadLastMergedUserId,
  saveLastMergedUserId,
} from '@/lib/notes/lastMergedUser';
import { formatUnknownError } from '@/lib/errors/formatUnknownError';
import { startNotesRealtimeSync, stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { useAuthStore, selectUserId } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';

/**
 * Signed-in notes sync: IndexedDB is the durable local store; remote is Supabase.
 * On first sign-in for an owner namespace, hydrate IndexedDB from a full remote snapshot, then
 * attach Realtime which triggers revision-aware pulls into IndexedDB.
 */
export function useNotesSync(enabled: boolean) {
  const userId = useAuthStore(selectUserId);
  const isAuthReady = useAuthStore((state) => state.isReady);
  const bootstrappedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || !isAuthReady) return;

    if (!userId) {
      bootstrappedRef.current = null;
      stopNotesRealtimeSync();
      return;
    }

    let cancelled = false;

    const bootstrap = async () => {
      if (bootstrappedRef.current !== userId) {
        const lastMerged = loadLastMergedUserId();
        if (lastMerged != null && lastMerged !== userId) {
          await clearLocalUserDataForAccountSwitch(lastMerged);
        } else {
          useNotesStore.getState().setNotes([]);
        }
        useNotesStore.getState().setStatus('loading');

        await migrateLegacyLocalNotes(userId);
        if (cancelled) return;

        // The remote snapshot is the only step here that can fail for a reason the device could
        // ride out. IndexedDB already holds this account's notes on every run after the first, so
        // an unreachable cloud must degrade to "showing your local copy" rather than to an error
        // screen over data that is sitting right there — and realtime still attaches below, so a
        // recovered connection fixes it without the user doing anything.
        try {
          await hydrateIndexedDbFromRemote(userId);
          if (cancelled) return;
          useNotesStore.getState().clearSyncError();
          saveLastMergedUserId(userId);
          bootstrappedRef.current = userId;
        } catch (error: unknown) {
          if (cancelled) return;
          console.warn('[Notelikeus] Remote notes hydration failed:', error);
          useNotesStore
            .getState()
            .setSyncError(formatUnknownError(error, 'Could not sync notes. Please try again.'));
          // Not marked bootstrapped and lastMergedUserId not saved: the snapshot never landed, so
          // the next start must try it again rather than treat this session as hydrated.
          await loadLocalNotesIntoStore(userId);
          if (cancelled) return;
        }
      } else {
        await loadLocalNotesIntoStore(userId);
      }
      if (useNotesStore.getState().status === 'loading') {
        useNotesStore.getState().setStatus('ready');
      }
      startNotesRealtimeSync(userId);
    };

    // Everything that reaches here failed a *local* step — the account-switch wipe, the legacy
    // migration, or reading IndexedDB. There is nothing on the device to fall back to, so this is
    // the blocking error; cloud trouble is handled above and never lands here.
    void bootstrap().catch((error: unknown) => {
      if (cancelled) return;
      console.error('[Notelikeus] Notes sync startup failed:', error);
      useNotesStore
        .getState()
        .setError(formatUnknownError(error, 'Could not load your notes on this device.'));
    });

    return () => {
      cancelled = true;
      stopNotesRealtimeSync();
    };
  }, [enabled, isAuthReady, userId]);
}
