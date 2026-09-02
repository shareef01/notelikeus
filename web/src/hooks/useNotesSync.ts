import { useEffect, useRef } from 'react';
import { clearLocalUserDataForAccountSwitch } from '@/lib/bootstrap';
import { hydrateIndexedDbFromRemote, loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';
import {
  accountsMatch,
  loadLocalFirebaseSupabaseLink,
} from '@/lib/migration/accountIdentity';
import { ensureFirebaseSupabaseMigration } from '@/lib/migration/firebaseSupabaseMigration';
import { migrateLegacyLocalNotes } from '@/lib/notes/legacyLocalMigration';
import {
  loadLastMergedUserId,
  saveLastMergedUserId,
} from '@/lib/notes/lastMergedUser';
import { startNotesRealtimeSync, stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';
import { useAuthStore, selectUserId } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';

/**
 * Signed-in notes sync: IndexedDB is the durable local store; remote backend is Firebase or
 * Supabase (dev flag). On first sign-in for an owner namespace, hydrate IndexedDB from a full
 * remote snapshot, then attach the realtime listener which mirrors remote changes into IndexedDB.
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
        const linkedFirebaseUid = loadLocalFirebaseSupabaseLink()?.firebaseUid ?? null;
        if (
          lastMerged != null &&
          !accountsMatch(lastMerged, userId, linkedFirebaseUid)
        ) {
          clearLocalUserDataForAccountSwitch(lastMerged);
        } else {
          useNotesStore.getState().setNotes([]);
        }
        useNotesStore.getState().setStatus('loading');

        if (isSupabaseBackendEnabled()) {
          await ensureFirebaseSupabaseMigration(userId);
          if (cancelled) return;
        }

        await migrateLegacyLocalNotes(userId);
        if (cancelled) return;
        await hydrateIndexedDbFromRemote(userId);
        if (cancelled) return;
        saveLastMergedUserId(userId);
        bootstrappedRef.current = userId;
      } else {
        await loadLocalNotesIntoStore(userId);
      }
      startNotesRealtimeSync(userId);
    };

    void bootstrap().catch((error: unknown) => {
      if (cancelled) return;
      console.error('[Notelikeus] Notes sync startup failed:', error);
      useNotesStore
        .getState()
        .setError(error instanceof Error ? error.message : 'Could not start syncing notes');
    });

    return () => {
      cancelled = true;
      stopNotesRealtimeSync();
    };
  }, [enabled, isAuthReady, userId]);
}
