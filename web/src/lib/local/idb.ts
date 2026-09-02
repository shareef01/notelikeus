import { META_STORE, NOTES_DB_NAME, NOTES_DB_VERSION, NOTES_STORE } from '@/lib/local/constants';

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

/** Test-only: close and reset the singleton so each test gets a fresh DB. */
export async function resetNotesDatabaseForTests(): Promise<void> {
  if (dbPromise) {
    const db = await dbPromise;
    db.close();
    dbPromise = null;
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
