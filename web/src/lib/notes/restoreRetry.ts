import { restoreCloudNote } from '@/lib/notes/tombstones';
import { useTombstoneStore } from '@/store/tombstoneStore';
import type { Note } from '@/types/note';

export function withoutRestoredDeletes(ids: string[]): string[] {
  const restored = new Set(useTombstoneStore.getState().restoredIds);
  if (restored.size === 0) return ids;
  return ids.filter((id) => !restored.has(id));
}

export function collectPreservedRestoredNotes(
  snapshotNoteIds: Set<string>,
  localNotes: Note[],
): Note[] {
  const restored = new Set(useTombstoneStore.getState().restoredIds);
  if (restored.size === 0) return [];
  const byId = new Map<string, Note>();
  for (const note of localNotes) {
    if (restored.has(note.id) && !snapshotNoteIds.has(note.id)) {
      byId.set(note.id, note);
    }
  }
  return [...byId.values()];
}

/**
 * Retries `restore_note` for markers that survived process death or an earlier RPC failure.
 * Success clears the marker; failure keeps it for the next pull.
 */
export async function retryPendingCloudRestores(
  userId: string,
  localNotes: Note[],
): Promise<void> {
  const pending = useTombstoneStore.getState().restoredIds;
  if (pending.length === 0) return;
  const byId = new Map(localNotes.map((note) => [note.id, note]));
  for (const id of pending) {
    if (!useTombstoneStore.getState().isRestored(id)) continue;
    const local = byId.get(id);
    if (!local) continue;
    try {
      await restoreCloudNote(userId, local);
      useTombstoneStore.getState().clearRestored([id]);
    } catch {
      // Marker stays; the next snapshot or pull retries.
    }
  }
}
