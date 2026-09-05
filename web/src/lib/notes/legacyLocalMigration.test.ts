import { beforeEach, describe, expect, it, vi } from 'vitest';

const syncNotesWithCloud = vi.fn();
vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => ({
    syncNotesWithCloud: (...args: unknown[]) => syncNotesWithCloud(...args),
  }),
}));

import { resetKeyCacheForTests } from '@/lib/crypto/unlockMigration';
import { migrateLegacyLocalNotes, LEGACY_NOTES_STORAGE_KEY } from '@/lib/notes/legacyLocalMigration';
import { createEmptyNote } from '@/types/note';

const LEGACY_LOCK_KEY_STORAGE = 'notelikeus-lock-key';
const APP_KEY_INFO = 'notelikeus-locked-notes-v1';

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** Builds a locked note exactly as the removed locking feature persisted it. */
async function lockedNote(id: string, secrets: { title: string; content: string }) {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  localStorage.setItem(LEGACY_LOCK_KEY_STORAGE, bytesToBase64(raw));
  const key = await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(APP_KEY_INFO) },
    key,
    new TextEncoder().encode(JSON.stringify({ ...secrets, checklist: [] })),
  );
  return {
    ...createEmptyNote({ id, localId: Number(id) }),
    title: '',
    content: '',
    checklist: [],
    isLocked: true,
    lockedBlob: { v: 1 as const, iv: bytesToBase64(iv), ct: bytesToBase64(new Uint8Array(ct)) },
  };
}

function seedLegacyStore(notes: unknown[]) {
  localStorage.setItem(LEGACY_NOTES_STORAGE_KEY, JSON.stringify({ state: { notes } }));
}

describe('migrateLegacyLocalNotes', () => {
  beforeEach(() => {
    localStorage.clear();
    syncNotesWithCloud.mockReset();
    syncNotesWithCloud.mockResolvedValue({ changes: 0, merged: [], remoteIds: [] });
    resetKeyCacheForTests();
  });

  it('migrates and clears the legacy store when every note is readable', async () => {
    const note = await lockedNote('1', { title: 'Hidden', content: 'Secret' });
    seedLegacyStore([note]);

    await migrateLegacyLocalNotes('uid');

    expect(syncNotesWithCloud).toHaveBeenCalledTimes(1);
    const uploaded = syncNotesWithCloud.mock.calls[0][1] as Array<{ title: string }>;
    expect(uploaded.map((n) => n.title)).toEqual(['Hidden']);
    expect(localStorage.getItem(LEGACY_NOTES_STORAGE_KEY)).toBeNull();
  });

  it('keeps the legacy store when a note cannot be decrypted, so the ciphertext survives', async () => {
    const note = await lockedNote('1', { title: 'Hidden', content: 'Secret' });
    seedLegacyStore([note]);
    // The key is unavailable this launch — a different browser, cleared storage, or a transient
    // IndexedDB failure. Previously this uploaded a blank note and deleted the only ciphertext.
    localStorage.removeItem(LEGACY_LOCK_KEY_STORAGE);
    resetKeyCacheForTests();

    await migrateLegacyLocalNotes('uid');

    const remaining = localStorage.getItem(LEGACY_NOTES_STORAGE_KEY);
    expect(remaining).not.toBeNull();
    // The encrypted payload must still be there for a later launch to recover.
    expect(remaining).toContain('lockedBlob');
    expect(syncNotesWithCloud).not.toHaveBeenCalled();
  });

  it('uploads the readable notes but still keeps the store when one is unreadable', async () => {
    const readable = { ...createEmptyNote({ id: '2', localId: 2 }), title: 'Open' };
    const unreadable = {
      ...createEmptyNote({ id: '3', localId: 3 }),
      title: '',
      content: '',
      lockedBlob: { v: 1 as const, iv: bytesToBase64(new Uint8Array(12)), ct: 'bm90LXZhbGlk' },
    };
    seedLegacyStore([readable, unreadable]);

    await migrateLegacyLocalNotes('uid');

    const uploaded = syncNotesWithCloud.mock.calls[0][1] as Array<{ id: string }>;
    // The blank one must not reach the cloud — a blank copy would win over the real note.
    expect(uploaded.map((n) => n.id)).toEqual(['2']);
    expect(localStorage.getItem(LEGACY_NOTES_STORAGE_KEY)).not.toBeNull();
  });

  it('is a no-op once the legacy store is gone', async () => {
    await migrateLegacyLocalNotes('uid');
    expect(syncNotesWithCloud).not.toHaveBeenCalled();
  });
});
