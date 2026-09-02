import { firebaseRemoteNotesDataSource } from '@/lib/remote/firebaseRemoteNotesDataSource';
import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';

let activeOverride: RemoteNotesDataSource | null = null;

export function getRemoteNotesDataSource(): RemoteNotesDataSource {
  if (activeOverride) return activeOverride;
  return isSupabaseBackendEnabled()
    ? supabaseRemoteNotesDataSource
    : firebaseRemoteNotesDataSource;
}

/** Test-only hook for adapter parity tests. */
export function setRemoteNotesDataSourceForTests(source: RemoteNotesDataSource): void {
  activeOverride = source;
}

export function resetRemoteNotesDataSourceForTests(): void {
  activeOverride = null;
}
