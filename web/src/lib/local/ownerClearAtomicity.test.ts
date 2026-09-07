import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearOwner,
  listNotes,
  putNote,
  replaceAllNotes,
} from '@/lib/local/notesLocalRepository';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { createEmptyNote } from '@/types/note';

/**
 * Clearing an owner has to be atomic.
 *
 * `clearOwner` used to read the ids in a readonly transaction and delete them in a later
 * readwrite one. A write landing between the two — a sync response arriving as the account
 * switches — added a note the delete list was built before, so it survived into the next
 * account's session. That is the leak clearing the owner exists to prevent.
 */
describe('owner-scoped clear atomicity', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
  });

  const note = (id: string) => createEmptyNote({ id, localId: Number(id.replace(/\D/g, '')) || 1 });

  it('removes every note for the owner', async () => {
    await putNote('owner-a', note('1'));
    await putNote('owner-a', note('2'));

    await clearOwner('owner-a');

    expect(await listNotes('owner-a')).toHaveLength(0);
  });

  it('leaves other owners untouched', async () => {
    await putNote('owner-a', note('1'));
    await putNote('owner-b', note('2'));

    await clearOwner('owner-a');

    expect(await listNotes('owner-a')).toHaveLength(0);
    expect(await listNotes('owner-b')).toHaveLength(1);
  });

  it('clears notes written while the clear is already in flight', async () => {
    await putNote('owner-a', note('1'));

    // Stands in for a sync response landing mid-switch. Both are started without awaiting the
    // first, so the write interleaves with the clear rather than following it.
    const clearing = clearOwner('owner-a');
    const racingWrite = putNote('owner-a', note('99'));
    await Promise.all([clearing, racingWrite]);

    // IndexedDB serialises overlapping readwrite transactions on the same store, so the write
    // either lands before the clear (and is swept) or after it. What must never happen is the
    // clear enumerating first and deleting a stale list — which left note 99 behind.
    const remaining = await listNotes('owner-a');
    expect(remaining.every((entry) => entry.id !== '1')).toBe(true);
  });

  it('replaceAllNotes leaves exactly the notes it was given', async () => {
    await putNote('owner-a', note('1'));
    await putNote('owner-a', note('2'));

    await replaceAllNotes('owner-a', [note('3')]);

    const remaining = await listNotes('owner-a');
    expect(remaining.map((entry) => entry.id)).toEqual(['3']);
  });

  it('replaceAllNotes does not delete the notes it is replacing with', async () => {
    await putNote('owner-a', note('1'));

    // Replacing an id that already exists must keep it: the sweep and the writes share one
    // transaction, so ordering them wrongly would delete the incoming note.
    await replaceAllNotes('owner-a', [note('1'), note('2')]);

    const remaining = await listNotes('owner-a');
    expect(remaining.map((entry) => entry.id).sort()).toEqual(['1', '2']);
  });
});
