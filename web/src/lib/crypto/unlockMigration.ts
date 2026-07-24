import type { ChecklistItem } from '@/types/checklist';
import type { Note } from '@/types/note';

/**
 * One-way migration off the removed note-locking feature.
 *
 * Locked notes were persisted with title/content/checklist blanked and the real values held in
 * `lockedBlob`, encrypted under a device-local AES-GCM key in IndexedDB. Dropping the feature
 * without decrypting first would leave those notes permanently empty, so every read of persisted
 * state runs through here.
 *
 * This only works on the device that holds the key. A user who opens the app in a different
 * browser, or who cleared site data, has no way to recover that text — the key never left the
 * device. Keep this path in place indefinitely rather than deleting it a release later.
 */

const LEGACY_LOCK_KEY_STORAGE = 'notelikeus-lock-key';
const DB_NAME = 'notelikeus-crypto';
const DB_STORE = 'keys';
const DB_KEY = 'lock-key';
const APP_KEY_INFO = 'notelikeus-locked-notes-v1';

type LegacyLockedBlob = { v: 1; iv: string; ct: string };

export type MaybeLockedNote = Note & {
  lockedBlob?: LegacyLockedBlob;
  isLocked?: boolean;
};

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function isLegacyBlob(value: unknown): value is LegacyLockedBlob {
  if (!value || typeof value !== 'object') return false;
  const blob = value as Record<string, unknown>;
  return blob.v === 1 && typeof blob.iv === 'string' && typeof blob.ct === 'string';
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

async function loadLegacyKey(): Promise<CryptoKey | null> {
  const db = await openKeyDb();
  if (db) {
    const stored = await new Promise<unknown>((resolve) => {
      try {
        const request = db.transaction(DB_STORE, 'readonly').objectStore(DB_STORE).get(DB_KEY);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => resolve(undefined);
      } catch {
        resolve(undefined);
      }
    });
    db.close();
    if (stored && typeof stored === 'object' && 'type' in (stored as object)) {
      return stored as CryptoKey;
    }
  }

  // Older builds kept the raw key in localStorage before it moved to IndexedDB.
  try {
    const raw = localStorage.getItem(LEGACY_LOCK_KEY_STORAGE);
    if (!raw) return null;
    return await crypto.subtle.importKey('raw', base64ToBytes(raw), { name: 'AES-GCM' }, false, [
      'decrypt',
    ]);
  } catch {
    return null;
  }
}

let cachedKey: Promise<CryptoKey | null> | null = null;

async function decryptBlob(blob: LegacyLockedBlob): Promise<{
  title: string;
  content: string;
  checklist: ChecklistItem[];
} | null> {
  if (!cachedKey) cachedKey = loadLegacyKey();
  const key = await cachedKey;
  if (!key) return null;
  try {
    const plaintext = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: base64ToBytes(blob.iv),
        additionalData: new TextEncoder().encode(APP_KEY_INFO),
      },
      key,
      base64ToBytes(blob.ct),
    );
    const parsed = JSON.parse(new TextDecoder().decode(plaintext)) as {
      title?: unknown;
      content?: unknown;
      checklist?: unknown;
    };
    return {
      title: typeof parsed.title === 'string' ? parsed.title : '',
      content: typeof parsed.content === 'string' ? parsed.content : '',
      checklist: Array.isArray(parsed.checklist) ? (parsed.checklist as ChecklistItem[]) : [],
    };
  } catch {
    return null;
  }
}

/** Restores a previously locked note's text and strips the removed fields. */
export async function unlockPersistedNote(raw: MaybeLockedNote): Promise<Note> {
  const { lockedBlob, isLocked: _wasLocked, ...note } = raw;

  if (!isLegacyBlob(lockedBlob)) {
    return note as Note;
  }

  const secrets = await decryptBlob(lockedBlob);
  if (!secrets) {
    // Key is gone (different browser, cleared storage). The ciphertext is unrecoverable, so
    // keep the note rather than dropping it and let the user see it is empty.
    console.warn('[Notelikeus] A previously hidden note could not be unlocked on this device.');
    return note as Note;
  }

  return {
    ...(note as Note),
    title: secrets.title,
    content: secrets.content,
    checklist: secrets.checklist,
  };
}

export async function unlockPersistedNotes(notes: MaybeLockedNote[]): Promise<Note[]> {
  return Promise.all(notes.map((note) => unlockPersistedNote(note)));
}
