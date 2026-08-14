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

interface SingleUnlock {
  note: Note;
  /** True when this note carried a locked blob that could not be decrypted on this device. */
  unrecoverable: boolean;
}

async function unlockOne(raw: MaybeLockedNote): Promise<SingleUnlock> {
  const { lockedBlob, isLocked: _wasLocked, ...note } = raw;

  if (!isLegacyBlob(lockedBlob)) {
    return { note: note as Note, unrecoverable: false };
  }

  const secrets = await decryptBlob(lockedBlob);
  if (!secrets) {
    // Could not decrypt. That is not always permanent: loadLegacyKey() also returns null for a
    // transient IndexedDB failure (another tab holding a connection, private browsing), and
    // cachedKey memoises that for the rest of the session. Report it so the caller keeps the
    // source data instead of treating a blank note as the migrated result.
    return { note: note as Note, unrecoverable: true };
  }

  return {
    note: {
      ...(note as Note),
      title: secrets.title,
      content: secrets.content,
      checklist: secrets.checklist,
    },
    unrecoverable: false,
  };
}

/** Restores a previously locked note's text and strips the removed fields. */
export async function unlockPersistedNote(raw: MaybeLockedNote): Promise<Note> {
  return (await unlockOne(raw)).note;
}

export interface UnlockBatch {
  /** Never locked, or decrypted successfully. Safe to upload and to treat as migrated. */
  notes: Note[];
  /**
   * Carried a locked blob that would not decrypt. Their text is *not* in [notes] — it is still
   * only in the caller's source data, which must therefore be kept.
   */
  unrecoverable: Note[];
}

export async function unlockPersistedNotes(notes: MaybeLockedNote[]): Promise<UnlockBatch> {
  const results = await Promise.all(notes.map((note) => unlockOne(note)));
  return {
    notes: results.filter((r) => !r.unrecoverable).map((r) => r.note),
    unrecoverable: results.filter((r) => r.unrecoverable).map((r) => r.note),
  };
}

/** Test seam: clears the memoised key so a suite can simulate the key being unavailable. */
export function resetKeyCacheForTests(): void {
  cachedKey = null;
}
