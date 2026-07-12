/**
 * One-time migration: decrypts data that was encrypted by the previous
 * encrypted storage implementation, then re-saves it as plain JSON.
 *
 * After this runs once, the app uses plain localStorage exclusively.
 */

const STORAGE_KEYS = ['notelikeus-settings', 'notelikeus-ui'];

async function getKeyFromIndexedDB(): Promise<CryptoKey | null> {
  try {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('notelikeus-crypto-key', 1);
      request.onupgradeneeded = () => { /* ignore, may not exist */ };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const tx = db.transaction('keys', 'readonly');
    const store = tx.objectStore('keys');
    const record = await new Promise<any>((resolve, reject) => {
      const req = store.get('installation-key');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    db.close();
    if (!record) return null;
    return await crypto.subtle.importKey('jwk', record.data, { name: 'AES-GCM' }, false, ['decrypt']);
  } catch {
    return null;
  }
}

function base64ToBytes(base64: string): Uint8Array {
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

async function decrypt(payload: string, key: CryptoKey): Promise<string> {
  const dot = payload.indexOf('.');
  if (dot === -1) throw new Error('Invalid payload');
  const iv = base64ToBytes(payload.slice(0, dot));
  const ciphertext = base64ToBytes(payload.slice(dot + 1));
  const copy = new Uint8Array(ciphertext.length);
  copy.set(ciphertext);
  const decoded = await crypto.subtle.decrypt({ name: 'AES-GCM', iv } as AesGcmParams, key, copy);
  return new TextDecoder().decode(decoded);
}

function isEncrypted(value: string): boolean {
  return value.includes('.') && value.length > 20;
}

/**
 * Run once on app startup. Migrates encrypted localStorage entries to plain JSON.
 */
export async function migrateEncryptedStorage(): Promise<void> {
  const key = await getKeyFromIndexedDB();
  if (!key) {
    // No encryption key found — data is either plaintext or never existed
    return;
  }

  for (const storageKey of STORAGE_KEYS) {
    try {
      const raw = localStorage.getItem(storageKey);
      if (!raw || !isEncrypted(raw)) continue;

      const decrypted = await decrypt(raw, key);
      // Validate it's parseable JSON
      JSON.parse(decrypted);
      // Re-save as plaintext
      localStorage.setItem(storageKey, decrypted);
    } catch {
      // If decryption fails, remove the corrupted entry
      localStorage.removeItem(storageKey);
    }
  }

  // Clean up the IndexedDB key store
  try {
    indexedDB.deleteDatabase('notelikeus-crypto-key');
  } catch {
    // Ignore cleanup failures
  }
}
