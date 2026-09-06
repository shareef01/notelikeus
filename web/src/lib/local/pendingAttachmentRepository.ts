import { PENDING_ATTACHMENTS_STORE } from '@/lib/local/constants';
import { getNotesDatabase, withStore } from '@/lib/local/idb';

export interface PendingAttachmentRecord {
  ownerId: string;
  attachmentId: string;
  noteId: string | null;
  blob: Blob;
  mimeType: string;
  createdAt: number;
}

export async function putPendingAttachment(
  ownerId: string,
  attachmentId: string,
  blob: Blob,
  mimeType: string,
  noteId: string | null,
): Promise<void> {
  const record: PendingAttachmentRecord = {
    ownerId,
    attachmentId,
    noteId,
    blob,
    mimeType,
    createdAt: Date.now(),
  };
  await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => store.put(record));
}

export async function getPendingAttachment(
  ownerId: string,
  attachmentId: string,
): Promise<PendingAttachmentRecord | null> {
  const result = await withStore<PendingAttachmentRecord>(
    PENDING_ATTACHMENTS_STORE,
    'readonly',
    (store) => store.get([ownerId, attachmentId]),
  );
  return (result as PendingAttachmentRecord | undefined) ?? null;
}

export async function deletePendingAttachment(
  ownerId: string,
  attachmentId: string,
): Promise<void> {
  await withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) =>
    store.delete([ownerId, attachmentId]),
  );
}

export async function listPendingAttachmentsForNote(
  ownerId: string,
  noteId: string,
): Promise<PendingAttachmentRecord[]> {
  const records = await new Promise<PendingAttachmentRecord[]>((resolve, reject) => {
    void withStore(PENDING_ATTACHMENTS_STORE, 'readonly', (store) => {
      const index = store.index('ownerNote');
      return index.getAll([ownerId, noteId]) as IDBRequest<PendingAttachmentRecord[]>;
    })
      .then((result) => resolve((result as PendingAttachmentRecord[]) ?? []))
      .catch(reject);
  });
  return records;
}

export async function deletePendingAttachmentsForNote(
  ownerId: string,
  noteId: string,
): Promise<void> {
  const records = await listPendingAttachmentsForNote(ownerId, noteId);
  if (records.length === 0) return;
  const db = await getNotesDatabase();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PENDING_ATTACHMENTS_STORE, 'readwrite');
    const store = tx.objectStore(PENDING_ATTACHMENTS_STORE);
    for (const record of records) {
      store.delete([ownerId, record.attachmentId]);
    }
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('deletePendingAttachmentsForNote failed'));
  });
}

export async function bindPendingAttachmentNoteId(
  ownerId: string,
  attachmentId: string,
  noteId: string,
): Promise<void> {
  const existing = await getPendingAttachment(ownerId, attachmentId);
  if (!existing) return;
  await putPendingAttachment(ownerId, attachmentId, existing.blob, existing.mimeType, noteId);
}
