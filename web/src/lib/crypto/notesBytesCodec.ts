/**
 * IndexedDB note-body format: `NLN1` || IV(12) || ciphertext+tag (AES-GCM-256).
 *
 * Distinct from attachment `NLA1` so a swapped blob fails authentication under the wrong codec.
 * AAD binds ciphertext to `ownerId/noteId`.
 */

const MAGIC = new Uint8Array([0x4e, 0x4c, 0x4e, 0x31]); // NLN1
const IV_SIZE = 12;
const GCM_TAG_BYTES = 16;
const MIN_SEALED = MAGIC.length + IV_SIZE + GCM_TAG_BYTES;

function toBufferSource(bytes: Uint8Array): BufferSource {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy;
}

export function noteAad(ownerId: string, noteId: string): Uint8Array {
  return new TextEncoder().encode(`${ownerId}/${noteId}`);
}

export function looksSealedNote(payload: Uint8Array): boolean {
  if (payload.byteLength < MIN_SEALED) return false;
  for (let i = 0; i < MAGIC.length; i++) {
    if (payload[i] !== MAGIC[i]) return false;
  }
  return true;
}

export async function sealNoteBytes(
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
        additionalData: toBufferSource(aad),
      },
      key,
      toBufferSource(plaintext),
    ),
  );
  const out = new Uint8Array(MAGIC.length + iv.length + ciphertext.length);
  out.set(MAGIC, 0);
  out.set(iv, MAGIC.length);
  out.set(ciphertext, MAGIC.length + iv.length);
  return out;
}

export async function openNoteBytes(
  key: CryptoKey,
  payload: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  if (!looksSealedNote(payload)) {
    throw new Error('Not a sealed note body');
  }
  const iv = payload.subarray(MAGIC.length, MAGIC.length + IV_SIZE);
  const ciphertext = payload.subarray(MAGIC.length + IV_SIZE);
  return new Uint8Array(
    await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: toBufferSource(iv),
        additionalData: toBufferSource(aad),
      },
      key,
      toBufferSource(ciphertext),
    ),
  );
}
