import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  getNotesCryptoKey,
  resetNotesCryptoKeyCacheForTests,
  resetNotesCryptoKeyForTests,
} from '@/lib/crypto/notesCryptoKey';
import { noteAad, openNoteBytes, sealNoteBytes } from '@/lib/crypto/notesBytesCodec';

const DB_NAME = 'notelikeus-notes-crypto';
const DB_STORE = 'keys';
const DB_KEY = 'notes-aes';
const aad = noteAad('owner', 'note-1');

/**
 * Functional key identity. Object identity says nothing here: IndexedDB hands back a structured
 * clone, so a key read from the store is never `===` the one that was written even when the key
 * material is the same.
 */
async function interchangeable(seal: CryptoKey, open: CryptoKey): Promise<boolean> {
  const sealed = await sealNoteBytes(seal, new TextEncoder().encode('probe'), aad);
  try {
    return new TextDecoder().decode(await openNoteBytes(open, sealed, aad)) === 'probe';
  } catch {
    return false;
  }
}

function writeSlot(value: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open(DB_NAME, 1);
    open.onupgradeneeded = () => {
      const db = open.result;
      if (!db.objectStoreNames.contains(DB_STORE)) db.createObjectStore(DB_STORE);
    };
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction(DB_STORE, 'readwrite');
      tx.objectStore(DB_STORE).put(value, DB_KEY);
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onerror = () => reject(tx.error);
    };
    open.onerror = () => reject(open.error);
  });
}

describe('device key slot is claimed, not overwritten', () => {
  beforeEach(async () => {
    await resetNotesCryptoKeyForTests();
  });

  it('a key already in the slot is returned instead of a fresh one', async () => {
    const first = await getNotesCryptoKey();
    expect(first).not.toBeNull();

    resetNotesCryptoKeyCacheForTests(); // next page load
    const second = await getNotesCryptoKey();
    expect(second).not.toBeNull();

    expect(await interchangeable(first!, second!)).toBe(true);
  });

  it('concurrent minting converges on one key, and it is the stored one', async () => {
    // Two realms racing to mint against an empty slot. The cache reset between the starts is
    // what makes these independent attempts rather than one shared promise.
    const a = getNotesCryptoKey();
    resetNotesCryptoKeyCacheForTests();
    const b = getNotesCryptoKey();
    const [keyA, keyB] = await Promise.all([a, b]);
    expect(keyA).not.toBeNull();
    expect(keyB).not.toBeNull();

    // Neither realm may walk away holding a key the slot does not hold — that is what strands
    // every row the losing realm goes on to seal.
    resetNotesCryptoKeyCacheForTests();
    const persisted = await getNotesCryptoKey();
    expect(await interchangeable(keyA!, persisted!)).toBe(true);
    expect(await interchangeable(keyB!, persisted!)).toBe(true);
  });

  it('refuses to mint over a corrupt record rather than orphaning sealed rows', async () => {
    await writeSlot({ notAKey: true });
    resetNotesCryptoKeyCacheForTests();

    expect(await getNotesCryptoKey()).toBeNull();

    // And the corrupt record is still there — not replaced by a usable-looking key that would
    // make every existing sealed row silently unopenable.
    const after = await new Promise<unknown>((resolve, reject) => {
      const open = indexedDB.open(DB_NAME, 1);
      open.onsuccess = () => {
        const db = open.result;
        const req = db.transaction(DB_STORE, 'readonly').objectStore(DB_STORE).get(DB_KEY);
        req.onsuccess = () => {
          db.close();
          resolve(req.result);
        };
        req.onerror = () => reject(req.error);
      };
      open.onerror = () => reject(open.error);
    });
    expect(after).toEqual({ notAKey: true });
  });
});
