import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  getNotesCryptoKey,
  resetNotesCryptoKeyForTests,
} from '@/lib/crypto/notesCryptoKey';
import { NOTES_DB_NAME, NOTES_STORE } from '@/lib/local/constants';
import { resetNotesDatabaseForTests, withStore } from '@/lib/local/idb';
import { listNotes, listStoredNotes, putNote } from '@/lib/local/notesLocalRepository';
import { loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';
import type { StoredNotePayload } from '@/lib/local/notesSealing';
import { createEmptyNote, type Note } from '@/types/note';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';

/** The stored row shape, mirrored locally — the repository keeps its own type internal. */
interface StoredRow {
  ownerId: string;
  id: string;
  note: StoredNotePayload;
}

const OWNER = 'owner-partial';

function note(id: string, localId: number): Note {
  return createEmptyNote({
    id,
    localId,
    title: `title-${id}`,
    content: `content-${id}`,
  });
}

/** Flips the last ciphertext byte, which breaks the GCM tag without touching anything else. */
async function corruptStoredNote(ownerId: string, id: string): Promise<void> {
  const raw = (await withStore<StoredRow | undefined>(NOTES_STORE, 'readonly', (store) =>
    store.get([ownerId, id]),
  )) as StoredRow;
  const bytes = new Uint8Array(raw.note.sealedBody!);
  bytes[bytes.length - 1] ^= 0xff;
  await withStore(NOTES_STORE, 'readwrite', (store) =>
    store.put({
      ownerId,
      id,
      note: { ...raw.note, sealedBody: bytes.buffer } satisfies StoredNotePayload,
    }),
  );
}

describe('unreadable rows do not hide readable ones', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    await resetNotesCryptoKeyForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    useNotesStore.getState().reset();
    useTombstoneStore.getState().reset?.();
  });

  it('listStoredNotes returns the readable rows and names the rest', async () => {
    await getNotesCryptoKey();
    await putNote(OWNER, note('note-A', 1));
    await putNote(OWNER, note('note-B', 2));
    await putNote(OWNER, note('note-C', 3));
    await corruptStoredNote(OWNER, 'note-B');

    const { notes, unreadable } = await listStoredNotes(OWNER);

    expect(notes.map((n) => n.id).sort()).toEqual(['note-A', 'note-C']);
    expect(unreadable).toEqual([{ id: 'note-B', reason: 'decrypt_failed' }]);
    // The readable ones are whole, not blanked.
    expect(notes.find((n) => n.id === 'note-A')?.title).toBe('title-note-A');
  });

  it('listNotes still refuses the whole read, for callers that merge by id', async () => {
    await getNotesCryptoKey();
    await putNote(OWNER, note('note-A', 1));
    await putNote(OWNER, note('note-B', 2));
    await corruptStoredNote(OWNER, 'note-B');

    await expect(listNotes(OWNER)).rejects.toThrowError(
      expect.objectContaining({ name: 'NoteDecryptionError', noteId: 'note-B' }),
    );
  });

  it('losing the whole key database leaves the notes on disk and the app usable', async () => {
    await getNotesCryptoKey();
    for (let i = 0; i < 5; i++) await putNote(OWNER, note(`note-${i}`, i + 1));

    // The sealing key lives in its own IndexedDB database. Dropping only that one is the
    // real-world shape of this: eviction, a failed upgrade, a restored profile.
    await resetNotesCryptoKeyForTests();

    const loaded = await loadLocalNotesIntoStore(OWNER);

    expect(loaded).toEqual([]);
    expect(useNotesStore.getState().unreadableNoteIds).toHaveLength(5);
    // Not a blocking error: the screen stays up and says what happened.
    expect(useNotesStore.getState().error).toBeNull();

    // Every row is still on disk, byte for byte, so a recovered key still recovers them.
    const rows = (await withStore<StoredRow[]>(NOTES_STORE, 'readonly', (store) => {
      return store.index('ownerId').getAll(OWNER) as IDBRequest<StoredRow[]>;
    })) as StoredRow[];
    expect(rows).toHaveLength(5);
    expect(rows.every((row) => row.note.sealedBody != null)).toBe(true);
  });

  it('a single bad row still lets the rest reach the store', async () => {
    await getNotesCryptoKey();
    await putNote(OWNER, note('note-A', 1));
    await putNote(OWNER, note('note-B', 2));
    await putNote(OWNER, note('note-C', 3));
    await corruptStoredNote(OWNER, 'note-B');

    const loaded = await loadLocalNotesIntoStore(OWNER);

    expect(loaded.map((n) => n.id).sort()).toEqual(['note-A', 'note-C']);
    expect(useNotesStore.getState().notes.map((n) => n.id).sort()).toEqual(['note-A', 'note-C']);
    expect(useNotesStore.getState().unreadableNoteIds).toEqual(['note-B']);
    expect(useNotesStore.getState().error).toBeNull();
  });

  it('clears the notice once the rows read cleanly again', async () => {
    await getNotesCryptoKey();
    await putNote(OWNER, note('note-A', 1));
    await putNote(OWNER, note('note-B', 2));
    await corruptStoredNote(OWNER, 'note-B');
    await loadLocalNotesIntoStore(OWNER);
    expect(useNotesStore.getState().unreadableNoteIds).toEqual(['note-B']);

    // Rewriting the bad row under the current key is what a repair would do.
    await putNote(OWNER, note('note-B', 2));
    await loadLocalNotesIntoStore(OWNER);

    expect(useNotesStore.getState().unreadableNoteIds).toEqual([]);
    expect(useNotesStore.getState().notes).toHaveLength(2);
  });
});
