import { META_STORE, NOTES_STORE } from '@/lib/local/constants';
import { withStore } from '@/lib/local/idb';
import type { Note } from '@/types/note';

export interface LocalOwnerMeta {
  ownerId: string;
  firebaseHydrated: boolean;
  hydratedAt: number | null;
  /** Supabase pull_changes cursor (Phase 4+). */
  lastRemoteRevision?: number;
  /** Per-note server revision for apply_note_change base_revision. */
  noteRevisions?: Record<string, number>;
}

interface StoredNoteRecord {
  ownerId: string;
  id: string;
  note: Note;
}

export async function listNotes(ownerId: string): Promise<Note[]> {
  const records = await new Promise<StoredNoteRecord[]>((resolve, reject) => {
    void withStore(NOTES_STORE, 'readonly', (store) => {
      const index = store.index('ownerId');
      return index.getAll(ownerId) as IDBRequest<StoredNoteRecord[]>;
    })
      .then((result) => resolve((result as StoredNoteRecord[]) ?? []))
      .catch(reject);
  });
  return records.map((record) => record.note);
}

export async function putNote(ownerId: string, note: Note): Promise<void> {
  const record: StoredNoteRecord = { ownerId, id: note.id, note };
  await withStore(NOTES_STORE, 'readwrite', (store) => store.put(record));
}

export async function putNotes(ownerId: string, notes: Note[]): Promise<void> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(NOTES_STORE, 'readwrite');
    const store = tx.objectStore(NOTES_STORE);
    for (const note of notes) {
      store.put({ ownerId, id: note.id, note } satisfies StoredNoteRecord);
    }
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('putNotes failed'));
  });
}

export async function deleteNote(ownerId: string, noteId: string): Promise<void> {
  await withStore(NOTES_STORE, 'readwrite', (store) => store.delete([ownerId, noteId]));
}

export async function clearOwner(ownerId: string): Promise<void> {
  const records = await new Promise<StoredNoteRecord[]>((resolve, reject) => {
    void withStore(NOTES_STORE, 'readonly', (store) => {
      const index = store.index('ownerId');
      return index.getAll(ownerId) as IDBRequest<StoredNoteRecord[]>;
    })
      .then((result) => resolve((result as StoredNoteRecord[]) ?? []))
      .catch(reject);
  });
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, META_STORE], 'readwrite');
    const notes = tx.objectStore(NOTES_STORE);
    for (const record of records) {
      notes.delete([ownerId, record.id]);
    }
    tx.objectStore(META_STORE).delete(ownerId);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('clearOwner failed'));
  });
}

export async function replaceAllNotes(ownerId: string, notes: Note[]): Promise<void> {
  const existing = await listNotes(ownerId);
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(NOTES_STORE, 'readwrite');
    const store = tx.objectStore(NOTES_STORE);
    for (const record of existing) {
      store.delete([ownerId, record.id]);
    }
    for (const note of notes) {
      store.put({ ownerId, id: note.id, note } satisfies StoredNoteRecord);
    }
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('replaceAllNotes failed'));
  });
}

export async function getOwnerMeta(ownerId: string): Promise<LocalOwnerMeta | null> {
  const result = await withStore<LocalOwnerMeta>(META_STORE, 'readonly', (store) =>
    store.get(ownerId),
  );
  return (result as LocalOwnerMeta | undefined) ?? null;
}

export async function setOwnerMeta(
  ownerId: string,
  patch: Partial<
    Pick<
      LocalOwnerMeta,
      'firebaseHydrated' | 'hydratedAt' | 'lastRemoteRevision' | 'noteRevisions'
    >
  >,
): Promise<void> {
  const existing = (await getOwnerMeta(ownerId)) ?? {
    ownerId,
    firebaseHydrated: false,
    hydratedAt: null,
  };
  await withStore(META_STORE, 'readwrite', (store) =>
    store.put({ ...existing, ...patch, ownerId }),
  );
}
