import { META_STORE, NOTES_STORE } from '@/lib/local/constants';
import { withStore } from '@/lib/local/idb';
import type { Note } from '@/types/note';

export interface LocalOwnerMeta {
  ownerId: string;
  /** Legacy alias kept for existing IndexedDB rows. */
  firebaseHydrated: boolean;
  /** Phase 4+: remote snapshot hydration complete for this owner. */
  remoteHydrated?: boolean;
  hydratedAt: number | null;
  /** Supabase pull_changes cursor (Phase 4+). */
  lastRemoteRevision?: number;
  /** Per-note server revision for apply_note_change base_revision. */
  noteRevisions?: Record<string, number>;
  /** Cloud note IDs from the last complete snapshot. Survives process death. */
  knownCloudIds?: string[];
  /** Phase 6: IndexedDB namespace migrated from Firebase uid. */
  firebaseNamespaceMigrated?: boolean;
  migratedFromOwnerId?: string;
  migratedAt?: number;
  /** Phase 6: Firebase cloud snapshot imported into Supabase. */
  firebaseCloudImported?: boolean;
  firebaseCloudImportedAt?: number;
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

let abortNextPutNotes = false;

/** Test-only: abort the next {@link putNotes} transaction before it commits. */
export function abortNextPutNotesForTests(): void {
  abortNextPutNotes = true;
}

export async function putNotes(ownerId: string, notes: Note[]): Promise<void> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(NOTES_STORE, 'readwrite');
    const store = tx.objectStore(NOTES_STORE);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('putNotes failed'));
    // An IndexedDB transaction can abort without any request having errored — the browser
    // reclaiming storage, or an internal fault — and `error` does not fire for those. Without
    // this handler the promise never settled, which is worse than a rejection everywhere it is
    // awaited: `hydrateFromRemote` waits on it before the app reports ready, and `applyNotes`
    // rolls the optimistic UI back in `.catch`, so a hang leaves the store claiming a durable
    // write that never happened. Matches `withStore`, which has handled both since it was written.
    tx.onabort = () => reject(tx.error ?? new Error('putNotes aborted'));

    if (abortNextPutNotes) {
      abortNextPutNotes = false;
      tx.abort();
      return;
    }
    for (const note of notes) {
      store.put({ ownerId, id: note.id, note } satisfies StoredNoteRecord);
    }
  });
}

export async function deleteNote(ownerId: string, noteId: string): Promise<void> {
  await withStore(NOTES_STORE, 'readwrite', (store) => store.delete([ownerId, noteId]));
}

/**
 * Removes every note and the meta row for one owner, in a single transaction.
 *
 * Enumeration and deletion have to share the transaction. Reading the ids in a readonly
 * transaction and deleting them in a later readwrite one leaves a window in which an in-flight
 * write — a sync response landing as the account switches — adds a note that the delete list was
 * built before and therefore never removes. That note then survives into the next account's
 * session, which is exactly what clearing the owner exists to prevent.
 */
export async function clearOwner(ownerId: string): Promise<void> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, META_STORE], 'readwrite');
    const notes = tx.objectStore(NOTES_STORE);
    const cursorRequest = notes.index('ownerId').openKeyCursor(IDBKeyRange.only(ownerId));
    cursorRequest.onsuccess = () => {
      const cursor = cursorRequest.result;
      if (!cursor) return;
      notes.delete(cursor.primaryKey);
      cursor.continue();
    };
    tx.objectStore(META_STORE).delete(ownerId);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('clearOwner failed'));
    tx.onabort = () => reject(tx.error ?? new Error('clearOwner aborted'));
  });
}

/**
 * Replaces this owner's notes with [notes], enumerating and writing in one transaction.
 *
 * Same reason as [clearOwner]: a note written between a separate read and write would be missed
 * by the delete list and then survive a replacement that is supposed to be authoritative,
 * leaving a note the server never sent.
 */
export async function replaceAllNotes(ownerId: string, notes: Note[]): Promise<void> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(NOTES_STORE, 'readwrite');
    const store = tx.objectStore(NOTES_STORE);
    const cursorRequest = store.index('ownerId').openKeyCursor(IDBKeyRange.only(ownerId));
    cursorRequest.onsuccess = () => {
      const cursor = cursorRequest.result;
      if (cursor) {
        store.delete(cursor.primaryKey);
        cursor.continue();
        return;
      }
      // Writes go after the sweep completes, so a replacement note is never deleted by the
      // cursor that is still walking the same index.
      for (const note of notes) {
        store.put({ ownerId, id: note.id, note } satisfies StoredNoteRecord);
      }
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('replaceAllNotes failed'));
    tx.onabort = () => reject(tx.error ?? new Error('replaceAllNotes aborted'));
  });
}

export async function getOwnerMeta(ownerId: string): Promise<LocalOwnerMeta | null> {
  const result = await withStore<LocalOwnerMeta>(META_STORE, 'readonly', (store) =>
    store.get(ownerId),
  );
  return (result as LocalOwnerMeta | undefined) ?? null;
}

let abortNextRemoteApply = false;

/** Test-only: abort the next notes+cursor transaction before it commits. */
export function abortNextRemotePageApplyForTests(): void {
  abortNextRemoteApply = true;
}

export interface RemotePageApply {
  ownerId: string;
  upserts: Note[];
  deletedNoteIds: string[];
  noteRevisions: Record<string, number>;
  lastRemoteRevision: number;
  /** Cloud IDs from this apply. Omit to leave the persisted set unchanged. */
  knownCloudIds?: string[];
  replaceOwnerNotes?: boolean;
}

/**
 * Cursor N is durable if and only if the note/tombstone mirror through N is durable.
 * Attachment hydration is not part of this transaction.
 */
export async function applyRemotePageAtomically(page: RemotePageApply): Promise<void> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, META_STORE], 'readwrite');
    const notes = tx.objectStore(NOTES_STORE);
    const meta = tx.objectStore(META_STORE);

    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('applyRemotePageAtomically failed'));
    tx.onabort = () => reject(tx.error ?? new Error('applyRemotePageAtomically aborted'));

    if (abortNextRemoteApply) {
      abortNextRemoteApply = false;
      tx.abort();
      return;
    }

    const metaReq = meta.get(page.ownerId);
    metaReq.onsuccess = () => {
      const existing = (metaReq.result as LocalOwnerMeta | undefined) ?? {
        ownerId: page.ownerId,
        firebaseHydrated: false,
        hydratedAt: null,
      };
      const currentCursor = existing.lastRemoteRevision ?? 0;
      if (page.lastRemoteRevision < currentCursor) {
        return;
      }

      const writePage = () => {
        for (const note of page.upserts) {
          notes.put({ ownerId: page.ownerId, id: note.id, note } satisfies StoredNoteRecord);
        }
        for (const noteId of page.deletedNoteIds) {
          notes.delete([page.ownerId, noteId]);
        }
        meta.put({
          ...existing,
          ownerId: page.ownerId,
          noteRevisions: page.noteRevisions,
          lastRemoteRevision: page.lastRemoteRevision,
          ...(page.knownCloudIds !== undefined ? { knownCloudIds: page.knownCloudIds } : {}),
        } satisfies LocalOwnerMeta);
      };

      if (!page.replaceOwnerNotes) {
        writePage();
        return;
      }

      const existingReq = notes.index('ownerId').getAll(page.ownerId);
      existingReq.onsuccess = () => {
        for (const record of (existingReq.result as StoredNoteRecord[] | undefined) ?? []) {
          notes.delete([page.ownerId, record.id]);
        }
        writePage();
      };
    };
  });
}

export async function applyRemoteSnapshotAtomically(args: {
  ownerId: string;
  notes: Note[];
  deletedNoteIds: string[];
  noteRevisions: Record<string, number>;
  lastRemoteRevision: number;
  knownCloudIds?: string[];
}): Promise<void> {
  await applyRemotePageAtomically({
    ownerId: args.ownerId,
    upserts: args.notes,
    deletedNoteIds: args.deletedNoteIds,
    noteRevisions: args.noteRevisions,
    lastRemoteRevision: args.lastRemoteRevision,
    knownCloudIds: args.knownCloudIds,
    replaceOwnerNotes: true,
  });
}

export async function setOwnerMeta(
  ownerId: string,
  patch: Partial<
    Pick<
      LocalOwnerMeta,
      'firebaseHydrated' | 'remoteHydrated' | 'hydratedAt' | 'lastRemoteRevision' | 'noteRevisions'
      | 'knownCloudIds'
      | 'firebaseNamespaceMigrated' | 'migratedFromOwnerId' | 'migratedAt'
      | 'firebaseCloudImported' | 'firebaseCloudImportedAt'
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
