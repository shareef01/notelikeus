import { describe, expect, it } from 'vitest';
import {
  looksSealedNote,
  noteAad,
  openNoteBytes,
  sealNoteBytes,
} from '@/lib/crypto/notesBytesCodec';

async function softwareKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, [
    'encrypt',
    'decrypt',
  ]);
}

describe('notesBytesCodec', () => {
  it('round-trips under matching AAD', async () => {
    const key = await softwareKey();
    const plain = new TextEncoder().encode(JSON.stringify({ title: 'T', content: 'C' }));
    const aad = noteAad('owner-1', 'note-1');
    const sealed = await sealNoteBytes(key, plain, aad);
    expect(looksSealedNote(sealed)).toBe(true);
    const opened = await openNoteBytes(key, sealed, aad);
    expect(new TextDecoder().decode(opened)).toContain('"title":"T"');
  });

  it('rejects wrong AAD', async () => {
    const key = await softwareKey();
    const sealed = await sealNoteBytes(
      key,
      new TextEncoder().encode('x'),
      noteAad('a', 'b'),
    );
    await expect(openNoteBytes(key, sealed, noteAad('a', 'other'))).rejects.toBeTruthy();
  });

  it('does not treat NLA1 attachment magic as a sealed note', () => {
    expect(looksSealedNote(new Uint8Array([0x4e, 0x4c, 0x41, 0x31, 1, 2, 3]))).toBe(false);
  });
});
