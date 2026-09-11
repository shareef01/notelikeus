/**
 * On-disk (IndexedDB) attachment blob format matching Android/Desktop:
 * `NLA1` || IV(12) || ciphertext+tag (AES-GCM-256).
 *
 * AAD binds the ciphertext to `ownerId/attachmentId` so a swapped blob fails authentication.
 */

const MAGIC = new Uint8Array([0x4e, 0x4c, 0x41, 0x31]); // NLA1
const IV_SIZE = 12;
const GCM_TAG_BYTES = 16;
const MIN_SEALED = MAGIC.length + IV_SIZE + GCM_TAG_BYTES;

export function attachmentAad(ownerId: string, attachmentId: string): Uint8Array {
  return new TextEncoder().encode(`${ownerId}/${attachmentId}`);
}

export function looksSealed(payload: Uint8Array): boolean {
  if (payload.byteLength < MIN_SEALED) return false;
  for (let i = 0; i < MAGIC.length; i++) {
    if (payload[i] !== MAGIC[i]) return false;
  }
  return true;
}

export async function sealAttachmentBytes(
  key: CryptoKey,
  plaintext: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(IV_SIZE));
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      {
        name: 'AES-GCM',
        iv,
        additionalData: aad,
      },
      key,
      plaintext,
    ),
  );
  const out = new Uint8Array(MAGIC.length + iv.length + ciphertext.length);
  out.set(MAGIC, 0);
  out.set(iv, MAGIC.length);
  out.set(ciphertext, MAGIC.length + iv.length);
  return out;
}

export async function openAttachmentBytes(
  key: CryptoKey,
  payload: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  if (!looksSealed(payload)) {
    throw new Error('Not a sealed attachment blob');
  }
  const iv = payload.subarray(MAGIC.length, MAGIC.length + IV_SIZE);
  const ciphertext = payload.subarray(MAGIC.length + IV_SIZE);
  return new Uint8Array(
    await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv,
        additionalData: aad,
      },
      key,
      ciphertext,
    ),
  );
}
