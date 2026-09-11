import { describe, expect, it } from 'vitest';
import {
  attachmentAad,
  looksSealed,
  openAttachmentBytes,
  sealAttachmentBytes,
} from '@/lib/crypto/attachmentBytesCodec';

async function softwareKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, [
    'encrypt',
    'decrypt',
  ]);
}

describe('attachmentBytesCodec', () => {
  it('round-trips plaintext under matching AAD', async () => {
    const key = await softwareKey();
    const plain = new TextEncoder().encode('sample-image-bytes');
    const aad = attachmentAad('owner-1', 'att-1');
    const sealed = await sealAttachmentBytes(key, plain, aad);

    expect(looksSealed(sealed)).toBe(true);
    const opened = await openAttachmentBytes(key, sealed, aad);
    expect(new TextDecoder().decode(opened)).toBe('sample-image-bytes');
  });

  it('rejects wrong AAD', async () => {
    const key = await softwareKey();
    const sealed = await sealAttachmentBytes(
      key,
      new Uint8Array([1, 2, 3]),
      attachmentAad('a', 'b'),
    );
    await expect(
      openAttachmentBytes(key, sealed, attachmentAad('a', 'other')),
    ).rejects.toBeTruthy();
  });

  it('does not treat a JPEG SOI as sealed', () => {
    expect(looksSealed(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe(false);
  });

  it('produces distinct IVs for identical plaintext', async () => {
    const key = await softwareKey();
    const aad = attachmentAad('o', 'a');
    const plain = new Uint8Array([9]);
    const first = await sealAttachmentBytes(key, plain, aad);
    const second = await sealAttachmentBytes(key, plain, aad);
    expect(first).not.toEqual(second);
  });
});
