import { beforeEach, describe, expect, it } from 'vitest';
import {
  resetKeyCacheForTests,
  unlockPersistedNote,
  unlockPersistedNotes,
} from '@/lib/crypto/unlockMigration';
import { createEmptyNote } from '@/types/note';

const LEGACY_LOCK_KEY_STORAGE = 'notelikeus-lock-key';
const APP_KEY_INFO = 'notelikeus-locked-notes-v1';

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** Reproduces exactly what the removed locking feature wrote to localStorage. */
async function writeLegacyLockedNote(secrets: {
  title: string;
  content: string;
  checklist: unknown[];
}) {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  localStorage.setItem(LEGACY_LOCK_KEY_STORAGE, bytesToBase64(raw));

  const key = await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(APP_KEY_INFO) },
    key,
    new TextEncoder().encode(JSON.stringify(secrets)),
  );

  return {
    ...createEmptyNote({ id: '1', localId: 1 }),
    // Locked notes were persisted with the body blanked out.
    title: '',
    content: '',
    checklist: [],
    isLocked: true,
    lockedBlob: { v: 1 as const, iv: bytesToBase64(iv), ct: bytesToBase64(new Uint8Array(ct)) },
  };
}

describe('unlockMigration', () => {
  beforeEach(() => {
    localStorage.clear();
    // The key is memoised at module scope, so without this a suite that loaded a real key
    // earlier keeps decrypting successfully and the "key is gone" cases pass vacuously.
    resetKeyCacheForTests();
  });

  it('restores the body of a note that was hidden before the feature was removed', async () => {
    const secrets = { title: 'Hidden', content: 'Secret body', checklist: [] };
    const persisted = await writeLegacyLockedNote(secrets);

    const restored = await unlockPersistedNote(persisted);

    expect(restored.title).toBe('Hidden');
    expect(restored.content).toBe('Secret body');
    expect((restored as { isLocked?: unknown }).isLocked).toBeUndefined();
    expect((restored as { lockedBlob?: unknown }).lockedBlob).toBeUndefined();
  });

  it('keeps the note rather than dropping it when the key is gone', async () => {
    const persisted = await writeLegacyLockedNote({
      title: 'Hidden',
      content: 'Secret',
      checklist: [],
    });
    // Simulate opening the app in a browser that never held the key.
    localStorage.removeItem(LEGACY_LOCK_KEY_STORAGE);
    resetKeyCacheForTests();

    const restored = await unlockPersistedNote(persisted);

    expect(restored.id).toBe('1');
    expect((restored as { lockedBlob?: unknown }).lockedBlob).toBeUndefined();
  });

  it('reports a note it could not decrypt instead of returning it as migrated', async () => {
    const persisted = await writeLegacyLockedNote({
      title: 'Hidden',
      content: 'Secret',
      checklist: [],
    });
    localStorage.removeItem(LEGACY_LOCK_KEY_STORAGE);
    resetKeyCacheForTests();

    const { notes, unrecoverable } = await unlockPersistedNotes([persisted]);

    // The blanked note must not be presented as a successfully migrated one — that is what let
    // the caller upload an empty copy and then delete the only surviving ciphertext.
    expect(notes).toHaveLength(0);
    expect(unrecoverable).toHaveLength(1);
    expect(unrecoverable[0].id).toBe('1');
  });

  it('separates decryptable notes from undecryptable ones in the same batch', async () => {
    const locked = await writeLegacyLockedNote({
      title: 'Hidden',
      content: 'Secret',
      checklist: [],
    });
    const plain = { ...createEmptyNote({ id: '2', localId: 2 }), title: 'Open' };

    const { notes, unrecoverable } = await unlockPersistedNotes([locked, plain]);

    // The key written by writeLegacyLockedNote is still present, so both should succeed.
    expect(unrecoverable).toHaveLength(0);
    expect(notes.map((note) => note.title).sort()).toEqual(['Hidden', 'Open']);
  });

  it('passes ordinary notes through untouched and strips a stale isLocked flag', async () => {
    const plain = { ...createEmptyNote({ id: '2', localId: 2 }), title: 'Open', isLocked: false };

    const { notes } = await unlockPersistedNotes([plain]);

    expect(notes[0].title).toBe('Open');
    expect((notes[0] as { isLocked?: unknown }).isLocked).toBeUndefined();
  });
});
