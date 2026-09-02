import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { GUEST_OWNER_ID, NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import {
  clearOwner,
  getOwnerMeta,
  listNotes,
  putNote,
  replaceAllNotes,
  setOwnerMeta,
} from '@/lib/local/notesLocalRepository';
import { createEmptyNote } from '@/types/note';

function makeNote(id: string, localId: number) {
  return createEmptyNote({ id, localId, title: `Note ${id}` });
}

describe('notesLocalRepository', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('persists and lists notes per owner namespace', async () => {
    await putNote('user-a', makeNote('1', 1));
    await putNote('user-b', makeNote('2', 2));

    const aNotes = await listNotes('user-a');
    const bNotes = await listNotes('user-b');

    expect(aNotes).toHaveLength(1);
    expect(aNotes[0]?.id).toBe('1');
    expect(bNotes).toHaveLength(1);
    expect(bNotes[0]?.id).toBe('2');
  });

  it('clears an owner namespace without touching another', async () => {
    await putNote('user-a', makeNote('1', 1));
    await putNote('user-b', makeNote('2', 2));
    await setOwnerMeta('user-a', { firebaseHydrated: true, hydratedAt: 1 });

    await clearOwner('user-a');

    expect(await listNotes('user-a')).toHaveLength(0);
    expect(await listNotes('user-b')).toHaveLength(1);
    expect(await getOwnerMeta('user-a')).toBeNull();
  });

  it('replaces all notes atomically for hydration', async () => {
    await putNote(GUEST_OWNER_ID, makeNote('1', 1));
    await replaceAllNotes(GUEST_OWNER_ID, [makeNote('9', 9), makeNote('10', 10)]);

    const notes = await listNotes(GUEST_OWNER_ID);
    const ids = notes.map((note) => note.id);
    expect(ids.sort()).toEqual(['10', '9']);
  });
});
