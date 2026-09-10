import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';
import { toError } from '@/lib/errors/formatUnknownError';

let activeOverride: RemoteNotesDataSource | null = null;
let sourcePromise: Promise<RemoteNotesDataSource> | null = null;

function loadSupabaseNotesSource(): Promise<RemoteNotesDataSource> {
  sourcePromise ??= import('@/lib/supabase/supabaseRemoteNotesDataSource').then(
    (module) => module.supabaseRemoteNotesDataSource,
  );
  return sourcePromise;
}

/**
 * Production source is Supabase, loaded on first use so the auth gate and guest shell do not
 * pay for supabase-js until a signed-in sync path actually runs.
 */
const lazySupabaseRemoteNotesDataSource: RemoteNotesDataSource = {
  subscribeToNotes(userId, onData, onError) {
    let cancelled = false;
    let unsub: (() => void) | undefined;
    void loadSupabaseNotesSource()
      .then((source) => {
        if (cancelled) return;
        unsub = source.subscribeToNotes(userId, onData, onError);
      })
      .catch((error: unknown) => {
        onError?.(toError(error, 'Notes sync failed'));
      });
    return () => {
      cancelled = true;
      unsub?.();
    };
  },
  async fetchAllNotes(userId) {
    return (await loadSupabaseNotesSource()).fetchAllNotes(userId);
  },
  async upsertNote(userId, note) {
    return (await loadSupabaseNotesSource()).upsertNote(userId, note);
  },
  async deleteNote(userId, noteId) {
    return (await loadSupabaseNotesSource()).deleteNote(userId, noteId);
  },
  async uploadAllNotes(userId, notes) {
    return (await loadSupabaseNotesSource()).uploadAllNotes(userId, notes);
  },
  async syncNotesWithCloud(userId, localNotes, previouslyKnownCloudIds) {
    return (await loadSupabaseNotesSource()).syncNotesWithCloud(
      userId,
      localNotes,
      previouslyKnownCloudIds,
    );
  },
};

export function getRemoteNotesDataSource(): RemoteNotesDataSource {
  if (activeOverride) return activeOverride;
  return lazySupabaseRemoteNotesDataSource;
}

/** Test-only hook for adapter parity tests. */
export function setRemoteNotesDataSourceForTests(source: RemoteNotesDataSource): void {
  activeOverride = source;
}

export function resetRemoteNotesDataSourceForTests(): void {
  activeOverride = null;
}
