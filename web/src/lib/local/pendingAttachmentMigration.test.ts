import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as idb from '@/lib/local/idb';
import * as codec from '@/lib/crypto/attachmentBytesCodec';
import { PENDING_ATTACHMENTS_STORE } from '@/lib/local/constants';
import { clearPendingAttachmentsForOwner, deletePendingAttachment, getPendingAttachment, putPendingAttachment } from '@/lib/local/pendingAttachmentRepository';
const owner = 'migration-owner';
const original = { ownerId: owner, noteId: 'n', attachmentId: 'image', blob: new TextEncoder().encode('old image').buffer,
  mimeType: 'image/png', sizeBytes: 9, createdAt: 1 };
beforeEach(async () => { vi.restoreAllMocks(); await idb.resetNotesDatabaseForTests(); });
afterEach(() => vi.restoreAllMocks());

async function pausedMigration() {
  await idb.withStore(PENDING_ATTACHMENTS_STORE, 'readwrite', (store) => store.put(original));
  let entered!: () => void;
  let resume!: () => void;
  const sealing = new Promise<void>((resolve) => { entered = resolve; });
  const gate = new Promise<void>((resolve) => { resume = resolve; });
  const seal = codec.sealAttachmentBytes;
  vi.spyOn(codec, 'sealAttachmentBytes').mockImplementationOnce(async (...args) => {
    entered(); await gate; return seal(...args);
  });
  const withStore = idb.withStore;
  let writes = 0;
  vi.spyOn(idb, 'withStore').mockImplementation(async (...args) => {
    const result = await withStore(...args);
    if (args[0] === PENDING_ATTACHMENTS_STORE && args[1] === 'readwrite') writes++;
    return result;
  });
  expect(await getPendingAttachment(owner, 'n', 'image')).not.toBeNull();
  await sealing;
  return async () => {
    const before = writes;
    resume();
    await vi.waitFor(() => expect(writes).toBeGreaterThan(before));
  };
}

it.each(['image', 'owner'])('does not resurrect legacy bytes after clearing %s', async (scope) => {
  const resume = await pausedMigration();
  if (scope === 'image') await deletePendingAttachment(owner, 'n', 'image');
  else await clearPendingAttachmentsForOwner(owner);
  await resume();
  expect(await idb.withStore(PENDING_ATTACHMENTS_STORE, 'readonly', (store) => store.get([owner, 'n', 'image']))).toBeUndefined();
});

it('does not overwrite a newer sealed image at the same key', async () => {
  const resume = await pausedMigration();
  await putPendingAttachment({ ...original, blob: new Blob(['new image']), createdAt: 2 });
  await resume();
  expect(await (await getPendingAttachment(owner, 'n', 'image'))?.blob.text()).toBe('new image');
});
