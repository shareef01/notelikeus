import { getSupabaseClient } from '@/lib/supabase/client';

/** Keep local tombstones long enough for offline devices to observe the delete. */
export const TOMBSTONE_TTL_MS = 180 * 24 * 60 * 60 * 1000;

/** Clears the caller's remote tombstone so an explicit undo can recreate the note. */
export async function deleteCloudTombstone(_userId: string, noteId: string): Promise<void> {
  const { error } = await getSupabaseClient().rpc('clear_note_tombstone', {
    p_note_id: noteId,
  });
  if (error) throw error;
}
