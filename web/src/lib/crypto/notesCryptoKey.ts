/**
 * Device-local AES-GCM key for IndexedDB note-body sealing.
 *
 * Dedicated DB — never the attachment key (`notelikeus-attachment-crypto`) or the legacy lock
 * key (`notelikeus-crypto`). Profile-at-rest only; not an XSS control.
 *
 * If a key record already exists but is unusable, does **not** mint a replacement.
 */

const DB_NAME = 'notelikeus-notes-crypto';
const DB_STORE = 'keys';
const DB_KEY = 'notes-aes';

let cachedKey: Promise<CryptoKey | null> | null = null;

function openKeyDb(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    if (typeof indexedDB === 'undefined') {
      resolve(null);
      return;
    }
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DB_NAME, 1);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(DB_STORE)) db.createObjectStore(DB_STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
}

function isCryptoKey(value: unknown): value is CryptoKey {
  return typeof value === 'object' && value !== null && 'type' in value && 'algorithm' in value;
}

async function readStoredKey(db: IDBDatabase): Promise<unknown> {
  return new Promise((resolve) => {
    try {
      const request = db.transaction(DB_STORE, 'readonly').objectStore(DB_STORE).get(DB_KEY);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(undefined);
    } catch {
      resolve(undefined);
    }
  });
}

async function writeStoredKey(db: IDBDatabase, key: CryptoKey): Promise<boolean> {
  return new Promise((resolve) => {
    try {
      const request = db.transaction(DB_STORE, 'readwrite').objectStore(DB_STORE).put(key, DB_KEY);
      request.onsuccess = () => resolve(true);
      request.onerror = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}

export async function getNotesCryptoKey(): Promise<CryptoKey | null> {
  if (!cachedKey) {
    cachedKey = (async () => {
      if (typeof crypto === 'undefined' || !crypto.subtle) return null;
      const db = await openKeyDb();
      if (!db) return null;
      try {
        const stored = await readStoredKey(db);
        if (isCryptoKey(stored)) return stored;
        if (stored !== undefined && stored !== null) return null;
        const key = await crypto.subtle.generateKey(
          { name: 'AES-GCM', length: 256 },
          false,
          ['encrypt', 'decrypt'],
        );
        const raced = await readStoredKey(db);
        if (isCryptoKey(raced)) return raced;
        if (raced !== undefined && raced !== null) return null;
        const written = await writeStoredKey(db, key);
        return written ? key : null;
      } finally {
        db.close();
      }
    })();
  }
  return cachedKey;
}

export function resetNotesCryptoKeyCacheForTests(): void {
  cachedKey = null;
}

export async function resetNotesCryptoKeyForTests(): Promise<void> {
  resetNotesCryptoKeyCacheForTests();
  if (typeof indexedDB === 'undefined') return;
  await new Promise<void>((resolve) => {
    const req = indexedDB.deleteDatabase(DB_NAME);
    req.onsuccess = () => resolve();
    req.onerror = () => resolve();
    req.onblocked = () => resolve();
  });
}
