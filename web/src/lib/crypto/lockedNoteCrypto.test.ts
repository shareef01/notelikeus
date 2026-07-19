import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearLockKey,
  decryptLockedSecrets,
  encryptLockedSecrets,
} from '@/lib/crypto/lockedNoteCrypto';
import { noteFromPersisted, noteToPersisted } from '@/lib/crypto/notesPersistCrypto';
import { createEmptyNote } from '@/types/note';

describe('lockedNoteCrypto', () => {
  beforeEach(() => {
    clearLockKey();
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
