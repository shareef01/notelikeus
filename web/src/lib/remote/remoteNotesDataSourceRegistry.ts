import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';

let activeOverride: RemoteNotesDataSource | null = null;

export function getRemoteNotesDataSource(): RemoteNotesDataSource {
  if (activeOverride) return activeOverride;
  return supabaseRemoteNotesDataSource;
}

/** Test-only hook for adapter parity tests. */
export function setRemoteNotesDataSourceForTests(source: RemoteNotesDataSource): void {
  activeOverride = source;
}

export function resetRemoteNotesDataSourceForTests(): void {
  activeOverride = null;
}
