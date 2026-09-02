import { fetchCloudTombstones } from '@/lib/firestore/tombstones';
import { fetchAllNotes } from '@/lib/remote/firebaseNotesFetch';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { isCloudSyncEligible } from '@/types/note';

/**
 * One-time import of Firebase cloud notes/tombstones into Supabase for the signed-in user.
 * Requires Firebase Auth session (read) and Supabase session (write).
 */
export async function importFirebaseCloudToSupabase(
  firebaseUid: string,
  supabaseUid: string,
): Promise<{ notesImported: number; tombstonesImported: number }> {
  const firebaseNotes = await fetchAllNotes(firebaseUid);
  const eligibleNotes = firebaseNotes.filter(isCloudSyncEligible);

  for (const note of eligibleNotes) {
    await supabaseRemoteNotesDataSource.upsertNote(supabaseUid, note);
  }

  const tombstones = await fetchCloudTombstones(firebaseUid);
  const liveIds = new Set(eligibleNotes.map((note) => note.id));
  let tombstonesImported = 0;
  for (const noteId of Object.keys(tombstones)) {
    if (liveIds.has(noteId)) continue;
    await supabaseRemoteNotesDataSource.deleteNote(supabaseUid, noteId);
    tombstonesImported += 1;
  }

  return { notesImported: eligibleNotes.length, tombstonesImported };
}

export async function supabaseCloudIsEmpty(): Promise<boolean> {
  const notes = await supabaseRemoteNotesDataSource.fetchAllNotes('');
  return notes.length === 0;
}
