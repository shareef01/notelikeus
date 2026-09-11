/**
 * Device-local AES-GCM key for pending attachment sealing.
 *
 * Stored as a non-extractable CryptoKey in a dedicated IndexedDB database — never the legacy
 * note-lock key in `notelikeus-crypto`. Protects against offline profile copies the same way
 * DPAPI does on Desktop; it is not an XSS control (same-origin script can still call decrypt).
 *
 * If a key record already exists but cannot be used, this module does **not** mint a replacement
 * (that would orphan sealed blobs). Missing key → generate once.
 */

const DB_NAME = 'notelikeus-attachment-crypto';
const DB_STORE = 'keys';
const DB_KEY = 'attachment-aes';

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

/**
 * Returns the attachment sealing key, generating it once if absent.
 * Returns null when IndexedDB/WebCrypto is unavailable, or when a corrupt non-key value is stored
 * (refuses to overwrite).
 */
export async function getAttachmentCryptoKey(): Promise<CryptoKey | null> {
  if (!cachedKey) {
    cachedKey = (async () => {
      if (typeof crypto === 'undefined' || !crypto.subtle) return null;
      const db = await openKeyDb();
      if (!db) return null;
      try {
        const stored = await readStoredKey(db);
        if (isCryptoKey(stored)) return stored;
        if (stored !== undefined && stored !== null) {
          // Corrupt / unexpected record — do not mint a replacement.
          return null;
        }
        const key = await crypto.subtle.generateKey(
          { name: 'AES-GCM', length: 256 },
          false,
          ['encrypt', 'decrypt'],
        );
        // Re-check before write so a racing tab that already minted wins.
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

/** Test seam: clears the memoised key promise. */
export function resetAttachmentCryptoKeyCacheForTests(): void {
  cachedKey = null;
}

/** Test-only: drop the attachment-crypto database so suites start clean. */
export async function resetAttachmentCryptoKeyForTests(): Promise<void> {
  resetAttachmentCryptoKeyCacheForTests();
  if (typeof indexedDB === 'undefined') return;
  await new Promise<void>((resolve) => {
    const req = indexedDB.deleteDatabase(DB_NAME);
    req.onsuccess = () => resolve();
    req.onerror = () => resolve();
    req.onblocked = () => resolve();
  });
}
