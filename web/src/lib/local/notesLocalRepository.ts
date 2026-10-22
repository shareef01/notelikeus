import {
  isSealedStoredNote,
  maybeMigrateStoredNote,
  NoteDecryptionError,
  openStoredNote,
  sealNoteForStorage,
  type StoredNotePayload,
} from '@/lib/local/notesSealing';
import { META_STORE, NOTES_STORE } from '@/lib/local/constants';
import { withStore } from '@/lib/local/idb';
import type { Note } from '@/types/note';
import { useSyncStatusStore } from '@/store/syncStatusStore';

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
  /**
   * Local edits not yet confirmed by the cloud, by note id. While a note is listed here its row is
   * the only copy of the user's latest text, so no remote apply may overwrite or delete it.
   */
  pendingPushes?: Record<string, PendingPush>;
}

/** A local edit waiting for the cloud — see {@link putLocalEdit}. */
export interface PendingPush {
  /**
   * The cloud revision the edit was made against: the server refuses the push if the note moved on
   * since, which is how a concurrent edit on another device is detected rather than overwritten.
   * Null for a note the cloud has never seen.
   */
  baseRevision: number | null;
  /**
   * Bumped by every local edit of the note. A push settles the marker only if this is still the
   * value it read, so an edit made while the push was in flight is never marked as confirmed.
   */
  seq: number;
}

/**
 * Per-owner mirror of the pending ids, for the synchronous merge paths that cannot await
 * IndexedDB. Refreshed after every transaction that changes the markers and on every list; the
 * durable copy in the meta row is what the IndexedDB write paths themselves consult.
 */
const pendingIdsByOwner = new Map<string, Set<string>>();

function mirrorPending(ownerId: string, pending: Record<string, PendingPush> | undefined): void {
  const ids = new Set(Object.keys(pending ?? {}));
  pendingIdsByOwner.set(ownerId, ids);
  useSyncStatusStore.getState().setPending(ownerId, ids);
}

/** Whether [noteId] holds a local edit the cloud has not confirmed, as far as this tab knows. */
export function hasPendingPush(ownerId: string, noteId: string): boolean {
  return pendingIdsByOwner.get(ownerId)?.has(noteId) ?? false;
}

function emptyMeta(ownerId: string): LocalOwnerMeta {
  return { ownerId, firebaseHydrated: false, hydratedAt: null };
}

interface StoredNoteRecord {
  ownerId: string;
  id: string;
  note: StoredNotePayload;
}

async function toStoredRecord(ownerId: string, note: Note): Promise<StoredNoteRecord> {
  return {
    ownerId,
    id: note.id,
    note: await sealNoteForStorage(ownerId, note),
  };
}

/** A row that is on disk and owned by this owner, but whose body could not be opened. */
export interface UnreadableNoteRef {
  id: string;
  reason: NoteDecryptionError['reason'];
}

export interface StoredNotesReadResult {
  notes: Note[];
  /**
   * Rows that failed to open, in store order. Never silently dropped: a caller that merges or
   * overwrites by id has to know these ids are taken, or it will write over a row whose
   * ciphertext is still intact and still recoverable.
   */
  unreadable: UnreadableNoteRef[];
}

async function readOwnerRecords(ownerId: string): Promise<StoredNoteRecord[]> {
  return new Promise<StoredNoteRecord[]>((resolve, reject) => {
    void withStore(NOTES_STORE, 'readonly', (store) => {
      const index = store.index('ownerId');
      return index.getAll(ownerId) as IDBRequest<StoredNoteRecord[]>;
    })
      .then((result) => resolve((result as StoredNoteRecord[]) ?? []))
      .catch(reject);
  });
}

/**
 * Reads every row for [ownerId], separating the ones that opened from the ones that did not.
 *
 * A row can fail to open without anything being wrong with the rest of the store — the sealing
 * key lives in its own IndexedDB database (`notelikeus-notes-crypto`), so the key can be lost
 * while every note row survives. Treating that as a whole-store failure is what
 * {@link listNotes} does, and it is right for callers that merge by id; it is wrong for the
 * screen, which then shows nothing at all when most notes are perfectly readable.
 *
 * Failure is still closed per row: an unopenable body is reported, never rendered as blank and
 * never re-sealed. The ciphertext stays on disk exactly as it was.
 */
/** Batch size for parallel note decryption on cold start without unbounded memory spikes. */
const DECRYPTION_BATCH_SIZE = 32;

export async function listStoredNotes(ownerId: string): Promise<StoredNotesReadResult> {
  const records = await readOwnerRecords(ownerId);

  const notes: Note[] = [];
  const unreadable: UnreadableNoteRef[] = [];

  for (let i = 0; i < records.length; i += DECRYPTION_BATCH_SIZE) {
    const chunk = records.slice(i, i + DECRYPTION_BATCH_SIZE);
    // eslint-disable-next-line no-await-in-loop -- controlled batching bounds concurrent crypto tasks and memory
    const batchResults = await Promise.all(
      chunk.map(async (record) => {
        try {
          const opened = await openStoredNote(ownerId, record.note);
          if (!isSealedStoredNote(record.note)) {
            void maybeMigrateStoredNote(ownerId, record.note, (next) =>
              replaceIfUnchanged(ownerId, record.id, record.note, next),
            );
          }
          return { ok: true as const, note: opened };
        } catch (error: unknown) {
          if (error instanceof NoteDecryptionError) {
            return { ok: false as const, ref: { id: error.noteId, reason: error.reason } };
          }
          throw error;
        }
      }),
    );

    for (const result of batchResults) {
      if (result.ok) {
        notes.push(result.note);
      } else {
        unreadable.push(result.ref);
      }
    }
  }

  return { notes, unreadable };
}

/**
 * Writes [next] over row [noteId] only if, inside this transaction, the row is still exactly
 * [expected] — the snapshot the caller read before awaiting anything.
 *
 * The legacy re-seal reads a row, then awaits the key and Web Crypto before writing. In that gap
 * the user can save a newer version, delete the note, or sign out and clear the account; a blind
 * put would then revert the edit, resurrect the note, or write one account's note back after it was
 * cleared (F-02). Comparing inside the writing transaction makes the write apply only to the row it
 * was computed from.
 */
async function replaceIfUnchanged(
  ownerId: string,
  noteId: string,
  expected: StoredNotePayload,
  next: StoredNotePayload,
): Promise<void> {
  const expectedJson = JSON.stringify(expected);
  await withStore(NOTES_STORE, 'readwrite', (store) => {
    const current = store.get([ownerId, noteId]);
    current.onsuccess = () => {
      const row = current.result as StoredNoteRecord | undefined;
      if (row && JSON.stringify(row.note) === expectedJson) {
        store.put({ ownerId, id: noteId, note: next } satisfies StoredNoteRecord);
      }
    };
    return current;
  });
}

/**
 * Every note for [ownerId], or a throw if any single row cannot be opened.
 *
 * All-or-nothing on purpose. The callers left on this function adopt, merge, or reconcile by
 * note id, and each of them would overwrite a row it could not see. Display paths want
 * {@link listStoredNotes} instead.
 */
export async function listNotes(ownerId: string): Promise<Note[]> {
  const { notes, unreadable } = await listStoredNotes(ownerId);
  const first = unreadable[0];
  if (first) {
    throw new NoteDecryptionError(first.id, first.reason);
  }
  return notes;
}

export async function putNote(ownerId: string, note: Note): Promise<void> {
  const record = await toStoredRecord(ownerId, note);
  await withStore(NOTES_STORE, 'readwrite', (store) => store.put(record));
}

/**
 * Runs [body] over the notes and meta stores in one `readwrite` transaction, handing it the
 * owner's meta row as it is *inside* that transaction. Every marker change goes through here, so a
 * marker decision is never made on a meta row another write has since replaced.
 *
 * [body] returns the meta row to write back, or null to leave it untouched. Callers seal before
 * calling: awaiting anything inside would let IndexedDB auto-commit between the read and the write.
 */
async function withNotesAndMeta(
  ownerId: string,
  body: (notes: IDBObjectStore, meta: LocalOwnerMeta) => LocalOwnerMeta | null,
): Promise<void> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  const outcome: { written: LocalOwnerMeta | null } = { written: null };
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, META_STORE], 'readwrite');
    const notes = tx.objectStore(NOTES_STORE);
    const metaStore = tx.objectStore(META_STORE);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('notes/meta transaction failed'));
    tx.onabort = () => reject(tx.error ?? new Error('notes/meta transaction aborted'));
    const metaReq = metaStore.get(ownerId);
    metaReq.onsuccess = () => {
      const meta = (metaReq.result as LocalOwnerMeta | undefined) ?? emptyMeta(ownerId);
      const next = body(notes, meta);
      if (next) {
        metaStore.put(next);
        outcome.written = next;
      }
    };
  });
  if (outcome.written) mirrorPending(ownerId, outcome.written.pendingPushes);
}

/**
 * Read-modify-write of the owner's meta row in one transaction. [update] gets the row as it is
 * inside that transaction and returns the row to write, or null to leave it. It must not await.
 */
export async function updateOwnerMeta(
  ownerId: string,
  update: (meta: LocalOwnerMeta) => LocalOwnerMeta | null,
): Promise<void> {
  await withNotesAndMeta(ownerId, (_notes, meta) => update(meta));
}

/**
 * Combines per-note revisions without ever losing a newer one (F-03).
 *
 * * Every revision in [incoming] is taken, unless [existing] already holds a higher one.
 * * [replace] (a full snapshot as of revision [horizon]): an id the snapshot does not carry is
 *   dropped — unless its revision is above [horizon], meaning it was learned after the snapshot
 *   was read.
 * * Ids in [deleted] are dropped, again unless their revision is above [horizon].
 */
export function mergeNoteRevisions(
  existing: Record<string, number>,
  incoming: Record<string, number>,
  { replace, horizon, deleted = [] }: { replace: boolean; horizon: number; deleted?: string[] },
): Record<string, number> {
  const next: Record<string, number> = {};
  for (const [id, revision] of Object.entries(existing)) {
    if (!replace || revision > horizon) next[id] = revision;
  }
  for (const [id, revision] of Object.entries(incoming)) {
    next[id] = Math.max(revision, existing[id] ?? revision);
  }
  for (const id of deleted) {
    if ((existing[id] ?? 0) <= horizon) delete next[id];
  }
  return next;
}

function mergeKnownCloudIds(
  existing: string[] | undefined,
  incoming: string[],
  deleted: string[],
): string[] {
  const gone = new Set(deleted);
  return [...new Set([...(existing ?? []), ...incoming])].filter((id) => !gone.has(id));
}

/**
 * Saves a user's edit and records it as waiting for the cloud, in one transaction.
 *
 * The marker keeps the base revision of the *first* unconfirmed edit: later edits build on the same
 * cloud version until a push succeeds, so the server can still tell whether someone else changed it.
 */
export async function putLocalEdit(ownerId: string, note: Note): Promise<void> {
  const record = await toStoredRecord(ownerId, note);
  await withNotesAndMeta(ownerId, (notes, meta) => {
    notes.put(record);
    const pending = { ...meta.pendingPushes };
    const previous = pending[note.id];
    pending[note.id] = {
      baseRevision: previous ? previous.baseRevision : (meta.noteRevisions?.[note.id] ?? null),
      seq: (previous?.seq ?? 0) + 1,
    };
    return { ...meta, pendingPushes: pending };
  });
}

export async function listPendingPushes(ownerId: string): Promise<Record<string, PendingPush>> {
  const meta = await getOwnerMeta(ownerId);
  mirrorPending(ownerId, meta?.pendingPushes);
  return { ...meta?.pendingPushes };
}

/** How many of [ownerId]'s notes hold an edit the cloud has not confirmed. */
export async function countPendingPushes(ownerId: string): Promise<number> {
  return Object.keys(await listPendingPushes(ownerId)).length;
}

/** A pending edit as one consistent pair: the marker and the row version it covers. */
export interface PendingEdit {
  marker: PendingPush;
  note: Note;
}

/**
 * Reads the marker and the row for [noteId] in one transaction, so the note returned is exactly
 * the version `marker.seq` describes. Null when nothing is pending or the row is gone.
 */
export async function readPendingEdit(ownerId: string, noteId: string): Promise<PendingEdit | null> {
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  const found: { marker?: PendingPush; record?: StoredNoteRecord } = {};
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, META_STORE], 'readonly');
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('readPendingEdit failed'));
    tx.onabort = () => reject(tx.error ?? new Error('readPendingEdit aborted'));
    const metaReq = tx.objectStore(META_STORE).get(ownerId);
    metaReq.onsuccess = () => {
      found.marker = (metaReq.result as LocalOwnerMeta | undefined)?.pendingPushes?.[noteId];
    };
    const noteReq = tx.objectStore(NOTES_STORE).get([ownerId, noteId]);
    noteReq.onsuccess = () => {
      found.record = noteReq.result as StoredNoteRecord | undefined;
    };
  });
  if (!found.marker || !found.record) return null;
  return { marker: found.marker, note: await openStoredNote(ownerId, found.record.note) };
}

/**
 * Records that the cloud accepted the version [seq] covered, as [accepted] at [revision].
 *
 * If no edit landed since, the marker goes and the row takes the server's stamp. If the user edited
 * again meanwhile, that newer edit stays pending — now based on the revision this push produced,
 * since that is the cloud version it builds on.
 */
export async function settlePendingPush(
  ownerId: string,
  seq: number,
  accepted: Note,
  revision: number | null,
): Promise<'settled' | 'rebased' | 'gone'> {
  const record = await toStoredRecord(ownerId, accepted);
  const result: { outcome: 'settled' | 'rebased' | 'gone' } = { outcome: 'gone' };
  await withNotesAndMeta(ownerId, (notes, meta) => {
    const marker = meta.pendingPushes?.[accepted.id];
    if (!marker) return null;
    const pending = { ...meta.pendingPushes };
    if (marker.seq === seq) {
      delete pending[accepted.id];
      notes.put(record);
      result.outcome = 'settled';
    } else {
      pending[accepted.id] = { ...marker, baseRevision: revision ?? marker.baseRevision };
      result.outcome = 'rebased';
    }
    return { ...meta, pendingPushes: pending };
  });
  return result.outcome;
}

/**
 * Resolves a push the cloud refused because the note changed elsewhere: [copy] keeps the local
 * version as a new pending note, and the original takes the cloud's [current] (or goes, when the
 * cloud deleted it). Refused — returning false, changing nothing — if the user edited the note
 * again since version [seq]; the next flush resolves that newer version instead.
 */
export async function resolveConflictedPush(
  ownerId: string,
  noteId: string,
  seq: number,
  copy: Note,
  current: Note | null,
): Promise<boolean> {
  const copyRecord = await toStoredRecord(ownerId, copy);
  const currentRecord = current ? await toStoredRecord(ownerId, current) : null;
  const result = { resolved: false };
  await withNotesAndMeta(ownerId, (notes, meta) => {
    const marker = meta.pendingPushes?.[noteId];
    if (!marker || marker.seq !== seq) return null;
    const pending = { ...meta.pendingPushes };
    delete pending[noteId];
    pending[copy.id] = { baseRevision: null, seq: 1 };
    notes.put(copyRecord);
    if (currentRecord) notes.put(currentRecord);
    else notes.delete([ownerId, noteId]);
    result.resolved = true;
    return { ...meta, pendingPushes: pending };
  });
  return result.resolved;
}

/** Forgets the marker for [noteId] — the note was deleted locally, so there is nothing to push. */
export async function dropPendingPush(ownerId: string, noteId: string): Promise<void> {
  await withNotesAndMeta(ownerId, (_notes, meta) => {
    if (!meta.pendingPushes?.[noteId]) return null;
    const pending = { ...meta.pendingPushes };
    delete pending[noteId];
    return { ...meta, pendingPushes: pending };
  });
}

let abortNextPutNotes = false;

/** Test-only: abort the next {@link putNotes} transaction before it commits. */
export function abortNextPutNotesForTests(): void {
  abortNextPutNotes = true;
}

export async function putNotes(ownerId: string, notes: Note[]): Promise<void> {
  const records = await Promise.all(notes.map((note) => toStoredRecord(ownerId, note)));
  const db = await import('@/lib/local/idb').then((m) => m.getNotesDatabase());
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction([NOTES_STORE, META_STORE], 'readwrite');
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
    // A bulk write is a mirror of some other copy — the UI store, a snapshot, an import — never
    // the user's own edit, so it must not replace a row whose edit the cloud has not confirmed.
    const metaReq = tx.objectStore(META_STORE).get(ownerId);
    metaReq.onsuccess = () => {
      const pending = (metaReq.result as LocalOwnerMeta | undefined)?.pendingPushes ?? {};
      for (const record of records) {
        if (!(record.id in pending)) store.put(record);
      }
    };
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
  mirrorPending(ownerId, undefined);
}

/**
 * Replaces this owner's notes with [notes], enumerating and writing in one transaction.
 *
 * Same reason as [clearOwner]: a note written between a separate read and write would be missed
 * by the delete list and then survive a replacement that is supposed to be authoritative,
 * leaving a note the server never sent.
 */
export async function replaceAllNotes(ownerId: string, notes: Note[]): Promise<void> {
  const records = await Promise.all(notes.map((note) => toStoredRecord(ownerId, note)));
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
      for (const record of records) {
        store.put(record);
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
  const records = await Promise.all(
    page.upserts.map((note) => toStoredRecord(page.ownerId, note)),
  );
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

      // A row with an unconfirmed local edit is the only copy of that edit. The cloud's version
      // of it — or its deletion — is decided when the edit is pushed, not by this page.
      const pending = existing.pendingPushes ?? {};
      const writePage = () => {
        for (const record of records) {
          if (!(record.id in pending)) notes.put(record);
        }
        for (const noteId of page.deletedNoteIds) {
          if (!(noteId in pending)) notes.delete([page.ownerId, noteId]);
        }
        // Merged against the meta read in this transaction, not replaced: the page's maps were
        // built from state loaded before its network round trip, and a push may have recorded a
        // newer revision since (F-03).
        const replace = page.replaceOwnerNotes === true;
        meta.put({
          ...existing,
          ownerId: page.ownerId,
          noteRevisions: mergeNoteRevisions(existing.noteRevisions ?? {}, page.noteRevisions, {
            replace,
            horizon: page.lastRemoteRevision,
            deleted: page.deletedNoteIds,
          }),
          lastRemoteRevision: page.lastRemoteRevision,
          ...(page.knownCloudIds !== undefined
            ? {
                knownCloudIds: replace
                  ? page.knownCloudIds
                  : mergeKnownCloudIds(existing.knownCloudIds, page.knownCloudIds, page.deletedNoteIds),
              }
            : {}),
        } satisfies LocalOwnerMeta);
      };

      if (!page.replaceOwnerNotes) {
        writePage();
        return;
      }

      const existingReq = notes.index('ownerId').getAll(page.ownerId);
      existingReq.onsuccess = () => {
        for (const record of (existingReq.result as StoredNoteRecord[] | undefined) ?? []) {
          if (!(record.id in pending)) notes.delete([page.ownerId, record.id]);
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
  // Read and write in one transaction: the row also carries `pendingPushes`, and a marker written
  // between a separate read and this put would be silently erased by the spread.
  await withNotesAndMeta(ownerId, (_notes, meta) => ({ ...meta, ...patch, ownerId }));
}
