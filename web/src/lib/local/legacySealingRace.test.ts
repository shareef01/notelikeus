import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NOTES_DB_NAME, NOTES_STORE } from '@/lib/local/constants';
import { resetNotesDatabaseForTests, withStore } from '@/lib/local/idb';
import {
  clearOwner,
  deleteNote,
  listStoredNotes,
  putNote,
} from '@/lib/local/notesLocalRepository';
import { isSealedStoredNote, openStoredNote, type StoredNotePayload } from '@/lib/local/notesSealing';
import { createEmptyNote, type Note } from '@/types/note';

/**
 * F-02: reading a legacy plaintext row starts a background re-seal of it. That re-seal awaits the
 * key and Web Crypto before writing, and anything can happen to the row meanwhile. Its write must
 * apply only to the row it read — never over a newer edit, and never to a row that is gone.
 *
 * The migration's own `encrypt` is held open with a gate, so each interleaving is exact.
 */
const OWNER = 'owner-legacy';

function legacyNote(title: string): Note {
  return createEmptyNote({ id: '1', localId: 1, title });
}

async function seedLegacyRow(note: Note): Promise<void> {
  await withStore(NOTES_STORE, 'readwrite', (store) =>
    store.put({ ownerId: OWNER, id: note.id, note: { ...note } satisfies StoredNotePayload }),
  );
}

async function rawRow(): Promise<{ note: StoredNotePayload } | undefined> {
  return withStore<{ note: StoredNotePayload } | undefined>(NOTES_STORE, 'readonly', (store) =>
    store.get([OWNER, '1']),
  ) as Promise<{ note: StoredNotePayload } | undefined>;
}

/** Holds the *first* `encrypt` call (the migration's) until released; later calls pass through. */
function gateFirstEncrypt() {
  const original = crypto.subtle.encrypt.bind(crypto.subtle);
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  let entered!: () => void;
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let calls = 0;
  const spy = vi.spyOn(crypto.subtle, 'encrypt').mockImplementation(async (...args) => {
    calls += 1;
    if (calls === 1) {
      entered();
      await released;
    }
    return original(...(args as Parameters<SubtleCrypto['encrypt']>));
  });
  return {
    started,
    async releaseAndSettle() {
      release();
      // The migration finishes asynchronously after its encrypt resolves.
      await new Promise((resolve) => setTimeout(resolve, 50));
    },
    spy,
  };
}

describe('F-02 legacy sealing migration', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not overwrite an edit saved while it was sealing', async () => {
    await seedLegacyRow(legacyNote('old text'));
    const gate = gateFirstEncrypt();

    await listStoredNotes(OWNER);
    await gate.started;
    await putNote(OWNER, { ...legacyNote('new text'), timestamp: 999 });
    await gate.releaseAndSettle();

    const row = await rawRow();
    expect((await openStoredNote(OWNER, row!.note)).title).toBe('new text');
  });

  it('does not bring back a note deleted while it was sealing', async () => {
    await seedLegacyRow(legacyNote('doomed'));
    const gate = gateFirstEncrypt();

    await listStoredNotes(OWNER);
    await gate.started;
    await deleteNote(OWNER, '1');
    await gate.releaseAndSettle();

    expect(await rawRow()).toBeUndefined();
  });

  it('does not write an account\'s note back after that account was cleared', async () => {
    await seedLegacyRow(legacyNote('private'));
    const gate = gateFirstEncrypt();

    await listStoredNotes(OWNER);
    await gate.started;
    await clearOwner(OWNER);
    await gate.releaseAndSettle();

    expect(await rawRow()).toBeUndefined();
  });

  it('still seals an untouched legacy row', async () => {
    await seedLegacyRow(legacyNote('quiet'));

    await listStoredNotes(OWNER);
    await vi.waitFor(async () => expect(isSealedStoredNote((await rawRow())!.note)).toBe(true));

    expect((await openStoredNote(OWNER, (await rawRow())!.note)).title).toBe('quiet');
  });
});
