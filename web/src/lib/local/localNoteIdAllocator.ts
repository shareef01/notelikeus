import { ID_SEQUENCE_STORE, NOTES_STORE } from '@/lib/local/constants';
import { getNotesDatabase } from '@/lib/local/idb';

const MAX_SAFE = Number.MAX_SAFE_INTEGER;

/** Injectable so tests can freeze or advance the time-based floor without waiting on the wall clock. */
let nowMs: () => number = () => Date.now();

export function setLocalNoteIdNowForTests(now: (() => number) | null): void {
  nowMs = now ?? (() => Date.now());
}

function timeBasedFloor(): number {
  return nowMs() * 1000;
}

interface SequenceRecord {
  ownerId: string;
  lastIssued: number;
}

interface StoredNoteRecord {
  ownerId: string;
  id: string;
  note: { localId?: number };
}

async function withOwnerIdLock<T>(ownerId: string, run: () => Promise<T>): Promise<T> {
  const locks = globalThis.navigator?.locks;
  if (!locks?.request) return run();
  return locks.request(`notelikeus-note-id:${ownerId}`, run);
}

/**
 * Atomically reserve `count` numeric localIds for this owner.
 *
 * Uses an exclusive IndexedDB transaction on the sequence record (and the notes store, to
 * observe existing ids). Optional Web Locks serialize tabs that support it; IDB remains the
 * source of truth for tabs that do not.
 *
 * Returns the first id of a contiguous range. Callers assign first, first+1, … first+count-1.
 */
export async function reserveLocalNoteIdRange(
  ownerId: string,
  count: number,
  existingMax = 0,
): Promise<number> {
  const n = Math.max(1, Math.trunc(count));
  return withOwnerIdLock(ownerId, () => reserveRangeInTransaction(ownerId, n, existingMax));
}

export async function allocateLocalNoteIdForOwner(
  ownerId: string,
  existingMax = 0,
): Promise<number> {
  return reserveLocalNoteIdRange(ownerId, 1, existingMax);
}

function reserveRangeInTransaction(
  ownerId: string,
  count: number,
  existingMax: number,
): Promise<number> {
  return getNotesDatabase().then(
    (db) =>
      new Promise<number>((resolve, reject) => {
        const tx = db.transaction([ID_SEQUENCE_STORE, NOTES_STORE], 'readwrite');
        const sequences = tx.objectStore(ID_SEQUENCE_STORE);
        const notes = tx.objectStore(NOTES_STORE);
        const index = notes.index('ownerId');

        let firstId = 0;
        let settled = false;

        const fail = (error: unknown) => {
          if (settled) return;
          settled = true;
          reject(error instanceof Error ? error : new Error('Note id allocation failed'));
        };

        tx.onerror = () => fail(tx.error ?? new Error('Note id allocation failed'));
        tx.onabort = () => fail(tx.error ?? new Error('Note id allocation aborted'));

        const seqReq = sequences.get(ownerId) as IDBRequest<SequenceRecord | undefined>;
        seqReq.onerror = () => fail(seqReq.error);
        seqReq.onsuccess = () => {
          const notesReq = index.getAll(ownerId) as IDBRequest<StoredNoteRecord[]>;
          notesReq.onerror = () => fail(notesReq.error);
          notesReq.onsuccess = () => {
            const lastIssued = seqReq.result?.lastIssued ?? 0;
            let maxExisting = Math.max(0, existingMax);
            for (const record of notesReq.result ?? []) {
              const localId = record.note?.localId;
              if (typeof localId === 'number' && Number.isFinite(localId)) {
                maxExisting = Math.max(maxExisting, localId);
              }
            }
            const floor = timeBasedFloor();
            const first = Math.max(lastIssued + 1, maxExisting + 1, floor);
            if (!Number.isFinite(first) || first > MAX_SAFE - count + 1) {
              fail(new Error('Note id space exhausted'));
              return;
            }
            const last = first + count - 1;
            firstId = first;
            sequences.put({ ownerId, lastIssued: last } satisfies SequenceRecord);
          };
        };

        tx.oncomplete = () => {
          if (settled) return;
          settled = true;
          resolve(firstId);
        };
      }),
  );
}
