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

export async function putPendingAttachment(record: PendingAttachmentRecord): Promise<void> {
  await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => {
    store.put(record);
  });
}

export async function getPendingAttachment(
  ownerId: string,
  noteId: string,
  attachmentId: string,
): Promise<PendingAttachmentRecord | null> {
  const result = await withStore<PendingAttachmentRecord | undefined>(
    PENDING_ATTACHMENTS_STORE,
    'readonly',
    (store) => store.get([ownerId, noteId, attachmentId]),
  );
  return result ?? null;
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
  return new Promise<PendingAttachmentRecord | null>((resolve, reject) => {
    const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readonly');
    const store = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    const index = store.index('attachmentId');
    const request = index.get(attachmentId);
    request.onsuccess = () => resolve(request.result ?? null);
    request.onerror = () => reject(request.error ?? new Error('findPendingAttachmentById failed'));
  });
}

export async function listPendingAttachmentsForOwner(
  ownerId: string,
): Promise<PendingAttachmentRecord[]> {
  const db = await getNotesDatabase();
  return new Promise<PendingAttachmentRecord[]>((resolve, reject) => {
    const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readonly');
    const store = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    const index = store.index('ownerId');
    const request = index.getAll(ownerId);
    request.onsuccess = () => resolve(request.result ?? []);
    request.onerror = () =>
      reject(request.error ?? new Error('listPendingAttachmentsForOwner failed'));
  });
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
