import type { Note } from '@/types/note';

/** Keep local tombstones long enough for offline devices to observe the delete. */
export const TOMBSTONE_TTL_MS = 180 * 24 * 60 * 60 * 1000;

/**
 * Atomically removes the owner's tombstone and writes the live note.
 * Callers persist a restore marker first so a crash cannot re-import the
 * cloud tombstone and hide the locally restored note.
 */
export async function restoreCloudNote(userId: string, note: Note): Promise<void> {
  const { getSupabaseClient } = await import('@/lib/supabase/client');
  const { noteToSupabaseRpcArgs } = await import('@/lib/supabase/supabaseNoteMapper');
  const { rememberNoteRevision } = await import('@/lib/supabase/revisionStore');
  const { data, error } = await getSupabaseClient().rpc(
    'restore_note',
    noteToSupabaseRpcArgs(note, null),
  );
  if (error) throw error;
  const result = (data ?? {}) as { status?: string; revision?: number };
  if (result.status !== 'applied') {
    throw new Error(`restore_note failed for note ${note.id}`);
  }
  if (result.revision != null) {
    await rememberNoteRevision(userId, note.id, result.revision);
  }
}
