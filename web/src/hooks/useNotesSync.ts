import { useEffect, useRef } from 'react';
import { clearLocalUserData } from '@/lib/bootstrap';
import { migrateLegacyLocalNotes } from '@/lib/notes/legacyLocalMigration';
import {
  loadLastMergedUserId,
  saveLastMergedUserId,
} from '@/lib/notes/lastMergedUser';
import { startNotesRealtimeSync, stopNotesRealtimeSync } from '@/lib/notes/notesSyncService';
import { useAuthStore, selectUserId } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';

/**
 * Notes are Firestore-only now (see notesStore.ts). "Launch" sync is this: as soon as a user is
 * signed in, subscribe the realtime listener, which Firestore serves from its persistent local
 * cache instantly and then reconciles against the server. There is no separate "exit" sync to
 * run — every edit already writes straight to Firestore (see noteActions.ts), so nothing is ever
 * left batched up waiting for the app to close.
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
        // Seeded from storage so the guard survives a reload, not just this mount.
        const lastMerged = loadLastMergedUserId();
        if (lastMerged != null && lastMerged !== userId) {
          // Account switch without a clean sign-out (e.g. a second tab signed into a different
          // account) — drop the prior account's labels/tombstones/any not-yet-migrated legacy
          // notes before touching this one, or they could leak into this account's Firestore
          // data or suppress/resurrect its notes. This also resets `notes`/`filters`, matching
          // the reset a clean sign-out already does.
          clearLocalUserData();
        } else {
          // Not a switch — just nothing loaded into the in-memory mirror yet this mount (first
          // load, or a page reload). Clear only the display, not the persisted filter/sort
          // preference, so it doesn't reset itself on every refresh.
          useNotesStore.getState().setNotes([]);
        }
        useNotesStore.getState().setStatus('loading');

        await migrateLegacyLocalNotes(userId);
        if (cancelled) return;
        saveLastMergedUserId(userId);
        bootstrappedRef.current = userId;
      }
      startNotesRealtimeSync(userId);
    };

    // Without this the store is left on 'loading' forever if the migration or the listener
    // setup throws, with the failure visible nowhere.
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
