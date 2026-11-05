import { beforeEach, expect, it, vi } from 'vitest';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import * as repository from '@/lib/local/pendingAttachmentRepository';
import { clearPendingAttachmentStore, storePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import { withPendingAttachments } from '@/lib/attachments/conflictCopyAttachments';
import { createEmptyNote } from '@/types/note';

const owner = 'conflict-owner';
const source = createEmptyNote({ id: 'source', localId: 1, attachments: [
  { id: 'one', noteId: 1, storagePath: 'pending:one', type: 'image' },
  { id: 'two', noteId: 1, storagePath: 'pending:two', type: 'image' },
] });
const copy = createEmptyNote({ id: 'copy', localId: 2 });

beforeEach(async () => {
  vi.restoreAllMocks();
  await resetNotesDatabaseForTests();
  clearPendingAttachmentStore();
  for (const id of ['one', 'two']) expect(await storePendingAttachment(id, new Blob([id]), 'image/png', source.id, owner)).toBe(true);
});

it('refuses the whole copy on a failed image write and cleans only partial new images', async () => {
  const put = repository.putPendingAttachment;
  const staged: string[] = [];
  vi.spyOn(repository, 'putPendingAttachment').mockImplementation(async (record) => {
    if (staged.length === 1) throw new Error('Quota exceeded');
    await put(record);
    staged.push(record.attachmentId);
  });
  await expect(withPendingAttachments(source, copy, owner)).rejects.toThrow('Could not stage');
  for (const id of ['one', 'two']) expect(await repository.getPendingAttachment(owner, source.id, id)).not.toBeNull();
  expect(await repository.getPendingAttachment(owner, copy.id, staged[0])).toBeNull();

  vi.restoreAllMocks();
  const retried = await withPendingAttachments(source, copy, owner);
  expect(retried.attachments).toHaveLength(2);
  for (const attachment of retried.attachments) expect(await repository.getPendingAttachment(owner, copy.id, attachment.id)).not.toBeNull();
});

it('refuses to resolve a conflict when an original image cannot be read', async () => {
  clearPendingAttachmentStore();
  await repository.deletePendingAttachmentForOwner(owner, 'one', source.id);
  await expect(withPendingAttachments(source, copy, owner)).rejects.toThrow('Missing image');
  expect(await repository.getPendingAttachment(owner, source.id, 'two')).not.toBeNull();
});
