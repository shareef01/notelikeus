import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import * as notes from '@/lib/local/notesLocalRepository';
import * as sealing from '@/lib/local/notesSealing';
import { putPendingAttachment, getPendingAttachment } from '@/lib/local/pendingAttachmentRepository';
import { adoptGuestNotesIntoAccount } from '@/lib/local/adoptGuestNotes';
import { cleanAdoptedGuestNote } from '@/lib/local/guestAdoptionCleanup';
import { createEmptyNote } from '@/types/note';
const owner = 'adoption-owner';
const original = createEmptyNote({ id: 'guest', localId: 1, title: 'old text', timestamp: 1 });
const newer = { ...original, title: 'new text from another tab' };
beforeEach(async () => { vi.restoreAllMocks(); await resetNotesDatabaseForTests(); await notes.putNote(GUEST_OWNER_ID, original); });
afterEach(() => vi.restoreAllMocks());

it('retains newer guest edits and their images after destination verification', async () => {
  await putPendingAttachment({ ownerId: GUEST_OWNER_ID, noteId: original.id, attachmentId: 'image', blob: new Blob(['photo']), mimeType: 'image/png', sizeBytes: 5, createdAt: 1 });
  const list = notes.listNotes;
  let reads = 0;
  vi.spyOn(notes, 'listNotes').mockImplementation(async (namespace) => {
    const snapshot = await list(namespace);
    if (namespace === owner && ++reads === 2) await notes.putNote(GUEST_OWNER_ID, newer);
    return snapshot;
  });
  await adoptGuestNotesIntoAccount(owner, { requireIntent: false });
  expect(await list(GUEST_OWNER_ID)).toEqual([newer]);
  expect(await list(owner)).toEqual([original]);
  expect(await getPendingAttachment(GUEST_OWNER_ID, original.id, 'image')).not.toBeNull();
  expect(await getPendingAttachment(owner, original.id, 'image')).not.toBeNull();
});

it('checks encrypted row versions in the cleanup transaction, including same-timestamp edits', async () => {
  await notes.putNote(owner, original);
  let entered!: () => void;
  let resume!: () => void;
  const reading = new Promise<void>((resolve) => { entered = resolve; });
  const gate = new Promise<void>((resolve) => { resume = resolve; });
  const open = sealing.openStoredNote;
  vi.spyOn(sealing, 'openStoredNote').mockImplementationOnce(async (...args) => { entered(); await gate; return open(...args); });
  const cleanup = cleanAdoptedGuestNote(owner, original, []);
  await reading;
  await notes.putNote(GUEST_OWNER_ID, newer);
  resume();
  expect(await cleanup).toBe(false);
  expect(await notes.listNotes(GUEST_OWNER_ID)).toEqual([newer]);
});

it('retains the source when the destination disappeared before cleanup', async () => {
  await notes.putNote(owner, original);
  await notes.clearOwner(owner);
  expect(await cleanAdoptedGuestNote(owner, original, [])).toBe(false);
  expect(await notes.listNotes(GUEST_OWNER_ID)).toEqual([original]);
});
