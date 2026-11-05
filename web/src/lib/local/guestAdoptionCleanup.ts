import { GUEST_OWNER_ID, NOTES_STORE, PENDING_ATTACHMENTS_STORE } from '@/lib/local/constants';
import { getNotesDatabase, withStore } from '@/lib/local/idb';
import { openStoredNote, type StoredNotePayload } from '@/lib/local/notesSealing';
import type { Note } from '@/types/note';

interface StoredRow { ownerId: string; id: string; note: StoredNotePayload }

/** Includes encrypted bytes, so even same-timestamp edits invalidate the snapshot. */
function snapshotKey(value: unknown): string {
  return JSON.stringify(value, (_key, item: unknown) => {
    if (item instanceof ArrayBuffer) return [...new Uint8Array(item)];
    if (item && typeof item === 'object' && !Array.isArray(item)) {
      return Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b)));
    }
    return item;
  });
}

/** Remove a copied guest note and its images only while both note versions still match. */
export async function cleanAdoptedGuestNote(ownerId: string, expected: Note, attachmentIds: string[]): Promise<boolean> {
  const read = (owner: string) => withStore<StoredRow | undefined>(NOTES_STORE, 'readonly',
    (store) => store.get([owner, expected.id]));
  const [source, destination] = await Promise.all([read(GUEST_OWNER_ID), read(ownerId)]);
  if (!source || !destination) return false;
  const [sourceNote, destinationNote] = await Promise.all([
    openStoredNote(GUEST_OWNER_ID, source.note), openStoredNote(ownerId, destination.note),
  ]);
  if (snapshotKey(sourceNote) !== snapshotKey(expected) || snapshotKey(destinationNote) !== snapshotKey(expected)) return false;

  const db = await getNotesDatabase();
  return new Promise<boolean>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, PENDING_ATTACHMENTS_STORE], 'readwrite');
    const notes = tx.objectStore(NOTES_STORE);
    const attachments = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    let remaining = 2 + attachmentIds.length;
    let unchanged = true;
    let deleted = false;
    const checked = () => {
      if (--remaining !== 0 || !unchanged) return;
      notes.delete([GUEST_OWNER_ID, expected.id]);
      for (const id of attachmentIds) attachments.delete([GUEST_OWNER_ID, expected.id, id]);
      deleted = true;
    };
    for (const snapshot of [source, destination]) {
      const request = notes.get([snapshot.ownerId, expected.id]);
      request.onsuccess = () => {
        unchanged &&= snapshotKey(request.result) === snapshotKey(snapshot);
        checked();
      };
    }
    // An account switch or another tab may have removed a destination image since verification.
    for (const id of attachmentIds) {
      const request = attachments.get([ownerId, expected.id, id]);
      request.onsuccess = () => { unchanged &&= Boolean(request.result); checked(); };
    }
    tx.oncomplete = () => resolve(deleted);
    tx.onerror = () => reject(tx.error ?? new Error('Guest cleanup failed'));
    tx.onabort = () => reject(tx.error ?? new Error('Guest cleanup aborted'));
  });
}
