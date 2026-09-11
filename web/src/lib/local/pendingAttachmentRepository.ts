import {
  attachmentAad,
  looksSealed,
  openAttachmentBytes,
  sealAttachmentBytes,
} from '@/lib/crypto/attachmentBytesCodec';
import { getAttachmentCryptoKey } from '@/lib/crypto/attachmentCryptoKey';
import { PENDING_ATTACHMENTS_STORE } from '@/lib/local/constants';
import { getNotesDatabase, withStore } from '@/lib/local/idb';

export interface PendingAttachmentRecord {
  ownerId: string;
  noteId: string;
  attachmentId: string;
  blob: Blob;
  mimeType: string;
  sizeBytes: number;
  createdAt: number;
}

/** Raw IndexedDB shape — bytes may be sealed NLA1 or legacy plaintext (Blob or ArrayBuffer). */
interface StoredPendingAttachment {
  ownerId: string;
  noteId: string;
  attachmentId: string;
  /** Prefer ArrayBuffer for sealed payloads; legacy rows may still hold a Blob. */
  blob: Blob | ArrayBuffer;
  mimeType: string;
  sizeBytes: number;
  createdAt: number;
}

async function blobToBytes(value: unknown): Promise<Uint8Array> {
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  // Duck-type before `instanceof Blob`: IndexedDB + happy-dom can rehydrate a Blob that is not
  // an instance of the realm's Blob constructor.
  if (
    value &&
    typeof value === 'object' &&
    typeof (value as Blob).arrayBuffer === 'function'
  ) {
    return new Uint8Array(await (value as Blob).arrayBuffer());
  }
  if (typeof Blob !== 'undefined' && value instanceof Blob) {
    if (typeof Response !== 'undefined') {
      return new Uint8Array(await new Response(value).arrayBuffer());
    }
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
      reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'));
      reader.readAsArrayBuffer(value);
    });
  }
  if (value && typeof value === 'object') {
    const record = value as { buffer?: ArrayBuffer; data?: ArrayBuffer };
    if (record.buffer instanceof ArrayBuffer) return new Uint8Array(record.buffer);
    if (record.data instanceof ArrayBuffer) return new Uint8Array(record.data);
  }
  throw new Error('Unsupported pending attachment payload');
}

function bytesToArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

async function openStoredBlob(stored: StoredPendingAttachment): Promise<Blob | null> {
  const bytes = await blobToBytes(stored.blob);
  const aad = attachmentAad(stored.ownerId, stored.attachmentId);

  if (!looksSealed(bytes)) {
    // Dual-read: legacy plaintext. Seal via maybeMigratePlaintext when a key is available.
    if (stored.blob instanceof Blob) return stored.blob;
    return new Blob([bytesToArrayBuffer(bytes)], {
      type: stored.mimeType || 'application/octet-stream',
    });
  }

  const key = await getAttachmentCryptoKey();
  if (!key) return null;
  try {
    const plain = await openAttachmentBytes(key, bytes, aad);
    return new Blob([bytesToArrayBuffer(plain)], {
      type: stored.mimeType || 'application/octet-stream',
    });
  } catch {
    return null;
  }
}

async function sealRecord(record: PendingAttachmentRecord): Promise<StoredPendingAttachment> {
  const key = await getAttachmentCryptoKey();
  if (!key) {
    // WebCrypto/IDB unavailable — fall back to plaintext rather than drop the staging write.
    return record;
  }
  const plain = await blobToBytes(record.blob);
  const sealed = await sealAttachmentBytes(
    key,
    plain,
    attachmentAad(record.ownerId, record.attachmentId),
  );
  return {
    ...record,
    // ArrayBuffer clones cleanly through IndexedDB (unlike some Blob polyfills in tests).
    blob: bytesToArrayBuffer(sealed),
    // sizeBytes stays the plaintext size callers already computed.
  };
}

async function maybeMigratePlaintext(stored: StoredPendingAttachment): Promise<void> {
  const bytes = await blobToBytes(stored.blob);
  if (looksSealed(bytes)) return;
  const key = await getAttachmentCryptoKey();
  if (!key) return;
  try {
    const sealed = await sealAttachmentBytes(
      key,
      bytes,
      attachmentAad(stored.ownerId, stored.attachmentId),
    );
    const next: StoredPendingAttachment = {
      ...stored,
      blob: bytesToArrayBuffer(sealed),
    };
    await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => {
      store.put(next);
    });
  } catch {
    // Leave plaintext in place; dual-read still works.
  }
}

function toPublic(
  stored: StoredPendingAttachment,
  blob: Blob,
): PendingAttachmentRecord {
  return {
    ownerId: stored.ownerId,
    noteId: stored.noteId,
    attachmentId: stored.attachmentId,
    blob,
    mimeType: stored.mimeType,
    sizeBytes: stored.sizeBytes,
    createdAt: stored.createdAt,
  };
}

export async function putPendingAttachment(record: PendingAttachmentRecord): Promise<void> {
  const sealed = await sealRecord(record);
  await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => {
    store.put(sealed);
  });
}

export async function getPendingAttachment(
  ownerId: string,
  noteId: string,
  attachmentId: string,
): Promise<PendingAttachmentRecord | null> {
  const result = await withStore<StoredPendingAttachment | undefined>(
    PENDING_ATTACHMENTS_STORE,
    'readonly',
    (store) => store.get([ownerId, noteId, attachmentId]),
  );
  if (!result) return null;
  const opened = await openStoredBlob(result);
  if (!opened) return null;
  void maybeMigratePlaintext(result);
  return toPublic(result, opened);
}

export async function deletePendingAttachment(
  ownerId: string,
  noteId: string,
  attachmentId: string,
): Promise<void> {
  await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => {
    store.delete([ownerId, noteId, attachmentId]);
  });
}

export async function findPendingAttachmentById(
  attachmentId: string,
): Promise<PendingAttachmentRecord | null> {
  const db = await getNotesDatabase();
  const stored = await new Promise<StoredPendingAttachment | null>((resolve, reject) => {
    const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readonly');
    const store = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    const index = store.index('attachmentId');
    const request = index.get(attachmentId);
    request.onsuccess = () => resolve(request.result ?? null);
    request.onerror = () => reject(request.error ?? new Error('findPendingAttachmentById failed'));
  });
  if (!stored) return null;
  const opened = await openStoredBlob(stored);
  if (!opened) return null;
  void maybeMigratePlaintext(stored);
  return toPublic(stored, opened);
}

export async function listPendingAttachmentsForOwner(
  ownerId: string,
): Promise<PendingAttachmentRecord[]> {
  const db = await getNotesDatabase();
  const rows = await new Promise<StoredPendingAttachment[]>((resolve, reject) => {
    const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readonly');
    const store = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    const index = store.index('ownerId');
    const request = index.getAll(ownerId);
    request.onsuccess = () => resolve(request.result ?? []);
    request.onerror = () =>
      reject(request.error ?? new Error('listPendingAttachmentsForOwner failed'));
  });

  const out: PendingAttachmentRecord[] = [];
  for (const stored of rows) {
    const opened = await openStoredBlob(stored);
    if (!opened) continue;
    void maybeMigratePlaintext(stored);
    out.push(toPublic(stored, opened));
  }
  return out;
}

export async function clearPendingAttachmentsForOwner(ownerId: string): Promise<void> {
  const db = await getNotesDatabase();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readwrite');
    const store = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    const index = store.index('ownerId');
    const request = index.openKeyCursor(IDBKeyRange.only(ownerId));
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        store.delete(cursor.primaryKey);
        cursor.continue();
      }
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('clearPendingAttachmentsForOwner failed'));
    tx.onabort = () => reject(tx.error ?? new Error('clearPendingAttachmentsForOwner aborted'));
  });
}
