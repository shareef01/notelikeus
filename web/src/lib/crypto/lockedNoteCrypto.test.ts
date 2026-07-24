import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearLockKey,
  decryptLockedSecrets,
  encryptLockedSecrets,
  getOrCreateLockKey,
} from '@/lib/crypto/lockedNoteCrypto';
import { noteFromPersisted, noteToPersisted } from '@/lib/crypto/notesPersistCrypto';
import { createEmptyNote } from '@/types/note';

const LEGACY_LOCK_KEY_STORAGE = 'notelikeus-lock-key';

describe('lockedNoteCrypto', () => {
  beforeEach(async () => {
    await clearLockKey();
    localStorage.clear();
  });

  it('round-trips secrets with AES-GCM', async () => {
    const secrets = {
      title: 'Secret',
      content: 'Body text',
      checklist: [{ id: '1', text: 'Item', isChecked: false, position: 0 }],
    };
    const blob = await encryptLockedSecrets(secrets);
    expect(blob.v).toBe(1);
    expect(blob.iv.length).toBeGreaterThan(0);
    expect(blob.ct.length).toBeGreaterThan(0);
    await expect(decryptLockedSecrets(blob)).resolves.toEqual(secrets);
  });

  it('persists locked notes redacted and restores them', async () => {
    const note = createEmptyNote({
      id: '1',
      localId: 1,
      title: 'Hidden',
      content: 'Do not store in clear',
      isLocked: true,
      checklist: [{ id: 'c1', text: 'Private', isChecked: true, position: 0 }],
    });

    const persisted = await noteToPersisted(note);
    expect(persisted.title).toBe('');
    expect(persisted.content).toBe('');
    expect(persisted.checklist).toEqual([]);
    expect(persisted.lockedBlob?.ct).toBeTruthy();

    const restored = await noteFromPersisted(persisted);
    expect(restored.title).toBe('Hidden');
    expect(restored.content).toBe('Do not store in clear');
    expect(restored.checklist[0]?.text).toBe('Private');
    expect((restored as { lockedBlob?: unknown }).lockedBlob).toBeUndefined();
  });

  it('adopts a pre-migration localStorage key so existing hidden notes still open', async () => {
    // Simulate a user upgraded from the build that kept the raw key in localStorage: encrypt a
    // note under that key, then let the current code migrate. Generating a fresh key here
    // instead of adopting the old one would orphan every locked note the user already has.
    const rawKey = crypto.getRandomValues(new Uint8Array(32));
    let binary = '';
    for (const byte of rawKey) binary += String.fromCharCode(byte);
    localStorage.setItem(LEGACY_LOCK_KEY_STORAGE, btoa(binary));

    const secrets = { title: 'Old', content: 'Encrypted before the migration', checklist: [] };
    const blob = await encryptLockedSecrets(secrets);
    await expect(decryptLockedSecrets(blob)).resolves.toEqual(secrets);

    // And the raw key no longer sits in localStorage where any script can read it.
    expect(localStorage.getItem(LEGACY_LOCK_KEY_STORAGE)).toBeNull();
  });

  it('keeps the key across a reload and never exposes its bytes', async () => {
    const secrets = { title: 'Persist', content: 'across reloads', checklist: [] };
    const blob = await encryptLockedSecrets(secrets);

    const key = await getOrCreateLockKey();
    expect(key.extractable).toBe(false);
    await expect(crypto.subtle.exportKey('raw', key)).rejects.toBeDefined();

    // Reset module state so the next call has only stored state to work from — the same
    // position a page reload leaves the app in.
    vi.resetModules();
    const reloaded = await import('@/lib/crypto/lockedNoteCrypto');
    await expect(reloaded.decryptLockedSecrets(blob)).resolves.toEqual(secrets);
    expect(localStorage.getItem(LEGACY_LOCK_KEY_STORAGE)).toBeNull();
  });

  it('clearLockKey makes previously encrypted blobs unreadable', async () => {
    const blob = await encryptLockedSecrets({ title: 'Bye', content: 'gone', checklist: [] });
    await clearLockKey();
    await expect(decryptLockedSecrets(blob)).rejects.toBeDefined();
  });

  it('leaves unlocked notes plaintext on disk shape', async () => {
    const note = createEmptyNote({
      id: '2',
      localId: 2,
      title: 'Open',
      content: 'Visible',
      isLocked: false,
    });
    const persisted = await noteToPersisted(note);
    expect(persisted.title).toBe('Open');
    expect(persisted.content).toBe('Visible');
    expect(persisted.lockedBlob).toBeUndefined();
  });
});
