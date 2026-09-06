import {
  META_STORE,
  NOTES_DB_NAME,
  NOTES_DB_VERSION,
  NOTES_STORE,
  PENDING_ATTACHMENTS_STORE,
} from '@/lib/local/constants';

let dbPromise: Promise<IDBDatabase> | null = null;

function openNotesDatabase(): Promise<IDBDatabase> {
  if (typeof indexedDB === 'undefined') {
    return Promise.reject(new Error('IndexedDB is not available'));
  }
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(NOTES_DB_NAME, NOTES_DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(NOTES_STORE)) {
        const notes = db.createObjectStore(NOTES_STORE, { keyPath: ['ownerId', 'id'] });
        notes.createIndex('ownerId', 'ownerId', { unique: false });
      }
      if (!db.objectStoreNames.contains(META_STORE)) {
        db.createObjectStore(META_STORE, { keyPath: 'ownerId' });
      }
      if (!db.objectStoreNames.contains(PENDING_ATTACHMENTS_STORE)) {
        const pending = db.createObjectStore(PENDING_ATTACHMENTS_STORE, {
          keyPath: ['ownerId', 'noteId', 'attachmentId'],
        });
        pending.createIndex('ownerId', 'ownerId', { unique: false });
        pending.createIndex('attachmentId', 'attachmentId', { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB open failed'));
    request.onblocked = () => reject(new Error('IndexedDB open blocked'));
  });
}

export function getNotesDatabase(): Promise<IDBDatabase> {
  if (!dbPromise) {
    dbPromise = openNotesDatabase();
  }
  return dbPromise;
}

/** Test-only: close connection without deleting the database to simulate app restart. */
export async function closeNotesDatabaseForTests(): Promise<void> {
  if (dbPromise) {
    try {
      const db = await dbPromise;
      db.close();
    } catch {
      // ignore
    }
    dbPromise = null;
  }
}

/** Test-only: close and reset the singleton so each test gets a fresh DB. */
export async function resetNotesDatabaseForTests(): Promise<void> {
  await closeNotesDatabaseForTests();
  if (typeof indexedDB !== 'undefined') {
    await new Promise<void>((resolve) => {
      const req = indexedDB.deleteDatabase(NOTES_DB_NAME);
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
      req.onblocked = () => resolve();
    });
  }
}

export async function withStore<T>(
  storeName: string,
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => IDBRequest<T> | void,
): Promise<T | void> {
  const db = await getNotesDatabase();
  return new Promise<T | void>((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const store = tx.objectStore(storeName);
    const request = run(store);
    tx.oncomplete = () => {
      if (request) {
        resolve(request.result as T);
      } else {
        resolve();
      }
    };
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed'));
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
  });
}
