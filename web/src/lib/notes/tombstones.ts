import type { Note } from '@/types/note';

/** Keep local tombstones long enough for offline devices to observe the delete. */
export const TOMBSTONE_TTL_MS = 180 * 24 * 60 * 60 * 1000;

/**
 * F-14: the server refused to undo a delete because the note moved on since this browser last saw it.
 *
 * `restore_note` is fenced on the revision it is given, exactly as `apply_note_change` and
 * `apply_note_delete` are. Without that fence an offline device's undo could put its older copy on
 * top of an edit made elsewhere, silently. The refusal is **terminal**: retrying with the same
 * baseline can only be refused again, so callers clear their restore marker and tell the user rather
 * than looping.
 */
export class RestoreRefusedError extends Error {
  readonly noteId: string;

  constructor(noteId: string) {
    super(
      `Couldn't undo the delete for note ${noteId}: it changed on another device, so the version ` +
        'there has been kept.',
    );
    this.name = 'RestoreRefusedError';
    this.noteId = noteId;
  }
}

/**
 * Atomically removes the owner's tombstone and writes the live note.
 * Callers persist a restore marker first so a crash cannot re-import the
 * cloud tombstone and hide the locally restored note.
 */
export async function restoreCloudNote(userId: string, note: Note): Promise<void> {
  const { getSupabaseClient } = await import('@/lib/supabase/client');
  const { noteToSupabaseRpcArgs } = await import('@/lib/supabase/supabaseNoteMapper');
  const { getNoteBaseRevision, loadRevisionState, rememberNoteRevision } = await import(
    '@/lib/supabase/revisionStore'
  );
  const { asWipeRefusal, wipeGenerationForPush } = await import('@/lib/supabase/wipeGeneration');
  const wipeGeneration = await wipeGenerationForPush(userId);
  // The revision this browser believes is there. Before F-14 this was a hard-coded null, which the
  // server treated as "no opinion" and overwrote; now it is the fence's whole input.
  const baseRevision = getNoteBaseRevision(await loadRevisionState(userId), note.id);
  const { data, error } = await getSupabaseClient().rpc(
    'restore_note',
    noteToSupabaseRpcArgs(note, baseRevision, wipeGeneration, { attachmentIds: false }),
  );
  // A restore is what puts a deleted note back, so it is exactly what a wipe refusal must stop.
  if (error) await asWipeRefusal(userId, error);
  const result = (data ?? {}) as {
    status?: string;
    revision?: number;
    current?: { revision?: number };
  };
  if (result.status === 'conflict') {
    // Learn what the server actually holds, so the merge that follows is not deciding from a
    // revision this browser has already been told is wrong.
    const remoteRevision = result.current?.revision;
    if (remoteRevision != null) {
      await rememberNoteRevision(userId, note.id, remoteRevision);
    }
    throw new RestoreRefusedError(note.id);
  }
  if (result.status !== 'applied') {
    throw new Error(`restore_note failed for note ${note.id}`);
  }
  if (result.revision != null) {
    await rememberNoteRevision(userId, note.id, result.revision);
  }
}
