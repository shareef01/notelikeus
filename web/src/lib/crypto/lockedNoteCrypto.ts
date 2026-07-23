import type { ChecklistItem } from '@/types/checklist';

/** Pre-migration location: the raw key sat here in base64, readable by any script. */
const LEGACY_LOCK_KEY_STORAGE = 'notelikeus-lock-key';
const DB_NAME = 'notelikeus-crypto';
const DB_STORE = 'keys';
const DB_KEY = 'lock-key';
const APP_KEY_INFO = 'notelikeus-locked-notes-v1';

export type LockedSecrets = {
  title: string;
  content: string;
  checklist: ChecklistItem[];
};

/** Persisted ciphertext for a locked note's sensitive fields. */
export type LockedBlob = {
  v: 1;
  iv: string;
  ct: string;
};

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/**
 * Imported non-extractable: script that gets into the page can still ask the key to decrypt,
 * but can no longer read the key material out and carry it off the device. That is the whole
 * point of the move off localStorage — it is not equivalent to Android's Keystore + biometric
 * binding, which gates *use* of the key on the user being present.
 */
async function importRawKey(raw: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, [
    'encrypt',
    'decrypt',
  ]);
}

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

function dbGet(db: IDBDatabase, key: string): Promise<unknown> {
  return new Promise((resolve) => {
    try {
      const request = db.transaction(DB_STORE, 'readonly').objectStore(DB_STORE).get(key);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(undefined);
    } catch {
      resolve(undefined);
    }
  });
}

function dbPut(db: IDBDatabase, key: string, value: unknown): Promise<void> {
  return new Promise((resolve) => {
    try {
      const request = db.transaction(DB_STORE, 'readwrite').objectStore(DB_STORE).put(value, key);
      request.onsuccess = () => resolve();
      request.onerror = () => resolve();
    } catch {
      resolve();
    }
  });
}

function dbDelete(db: IDBDatabase, key: string): Promise<void> {
  return new Promise((resolve) => {
    try {
      const request = db.transaction(DB_STORE, 'readwrite').objectStore(DB_STORE).delete(key);
      request.onsuccess = () => resolve();
      request.onerror = () => resolve();
    } catch {
      resolve();
    }
  });
}

function readLegacyRawKey(): Uint8Array<ArrayBuffer> | null {
  try {
    const existing = localStorage.getItem(LEGACY_LOCK_KEY_STORAGE);
    return existing ? base64ToBytes(existing) : null;
  } catch {
    return null;
  }
}

function dropLegacyRawKey(): void {
  try {
    localStorage.removeItem(LEGACY_LOCK_KEY_STORAGE);
  } catch {
    // ignore
  }
}

/** Session key when IndexedDB is unavailable (private mode, blocked storage, tests). */
let memoryKey: CryptoKey | null = null;
/** One resolution per session — avoids an IndexedDB round-trip per note. */
let keyPromise: Promise<CryptoKey> | null = null;

async function resolveLockKey(): Promise<CryptoKey> {
  const db = await openKeyDb();

  if (db) {
    const stored = await dbGet(db, DB_KEY);
    if (stored && typeof stored === 'object' && 'type' in (stored as object)) {
      db.close();
      return stored as CryptoKey;
    }
  }

  // Migrate a pre-existing localStorage key rather than generating a new one, which would
  // orphan every already-encrypted locked note.
  const legacyRaw = readLegacyRawKey();
  const key = legacyRaw
    ? await importRawKey(legacyRaw)
    : await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
        'encrypt',
        'decrypt',
      ]);

  if (db) {
    await dbPut(db, DB_KEY, key);
    db.close();
    if (legacyRaw) dropLegacyRawKey();
  } else {
    memoryKey = key;
  }

  return key;
}

/** Device-local AES key for locked-note payloads. Cleared on sign-out with user data. */
export async function getOrCreateLockKey(): Promise<CryptoKey> {
  if (memoryKey) return memoryKey;
  if (!keyPromise) {
    keyPromise = resolveLockKey().catch((error) => {
      keyPromise = null;
      throw error;
    });
  }
  return keyPromise;
}

/**
 * Fire-and-forget safe: returns a promise for callers that want to await, but sign-out paths
 * call it synchronously. IndexedDB serialises requests per database, so a key created for the
 * next account is still ordered after this delete.
 */
export function clearLockKey(): Promise<void> {
  memoryKey = null;
  keyPromise = null;
  dropLegacyRawKey();
  return openKeyDb().then(async (db) => {
    if (!db) return;
    await dbDelete(db, DB_KEY);
    db.close();
  });
}

export async function encryptLockedSecrets(secrets: LockedSecrets): Promise<LockedBlob> {
  const key = await getOrCreateLockKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = new TextEncoder().encode(JSON.stringify(secrets));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(APP_KEY_INFO) },
    key,
    plaintext,
  );
  return {
    v: 1,
    iv: bytesToBase64(iv),
    ct: bytesToBase64(new Uint8Array(ciphertext)),
  };
}

export async function decryptLockedSecrets(blob: LockedBlob): Promise<LockedSecrets> {
  if (blob.v !== 1) {
    throw new Error('Unsupported locked note blob version');
  }
  const key = await getOrCreateLockKey();
  const iv = base64ToBytes(blob.iv);
  const ct = base64ToBytes(blob.ct);
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(APP_KEY_INFO) },
    key,
    ct,
  );
  const parsed = JSON.parse(new TextDecoder().decode(plaintext)) as LockedSecrets;
  return {
    title: typeof parsed.title === 'string' ? parsed.title : '',
    content: typeof parsed.content === 'string' ? parsed.content : '',
    checklist: Array.isArray(parsed.checklist) ? parsed.checklist : [],
  };
}

export function isLockedBlob(value: unknown): value is LockedBlob {
  if (!value || typeof value !== 'object') return false;
  const blob = value as Record<string, unknown>;
  return blob.v === 1 && typeof blob.iv === 'string' && typeof blob.ct === 'string';
}
