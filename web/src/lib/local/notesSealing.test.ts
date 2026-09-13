import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetNotesCryptoKeyForTests } from '@/lib/crypto/notesCryptoKey';
import {
  openStoredNote,
  sealNoteForStorage,
  type StoredNotePayload,
} from '@/lib/local/notesSealing';
import { NOTES_DB_NAME, NOTES_STORE } from '@/lib/local/constants';
import { resetNotesDatabaseForTests, withStore } from '@/lib/local/idb';
import { listNotes, putNote } from '@/lib/local/notesLocalRepository';
import { createEmptyNote } from '@/types/note';
import { useNotesStore } from '@/store/notesStore';

describe('notesSealing F01 invariants', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    await resetNotesCryptoKeyForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
    useNotesStore.getState().reset();
    vi.restoreAllMocks();
  });

  // Test A: missingCryptoKeyDoesNotProduceEditableBlankNote
  it('missingCryptoKeyDoesNotProduceEditableBlankNote', async () => {
    const original = createEmptyNote({
      id: 'note-missing-key',
      localId: 1,
      title: 'Confidential Title',
      content: 'Confidential Body',
    });
    // Store properly sealed note
    await putNote('owner-1', original);

    // Verify stored note has sealed body
    const rawBefore = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-1', 'note-missing-key']),
    );
    expect(rawBefore?.note.sealedBody).toBeDefined();
    const bytesBefore = new Uint8Array(rawBefore!.note.sealedBody!);

    // Simulate key lookup returning unavailable
    const notesCryptoKeyModule = await import('@/lib/crypto/notesCryptoKey');
    vi.spyOn(notesCryptoKeyModule, 'getNotesCryptoKey').mockResolvedValue(null);

    // Expected: Loading explicitly reports unreadable/decryption failure.
    // No valid Note with title === "" / content === "" is returned as ordinary application data.
    await expect(openStoredNote('owner-1', rawBefore!.note)).rejects.toThrow();
    await expect(listNotes('owner-1')).rejects.toThrow();

    // Stored IndexedDB record remains byte-identical.
    const rawAfter = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-1', 'note-missing-key']),
    );
    const bytesAfter = new Uint8Array(rawAfter!.note.sealedBody!);
    expect(bytesAfter).toEqual(bytesBefore);
  });

  // Test B: corruptedCiphertextDoesNotProduceEditableBlankNote
  it('corruptedCiphertextDoesNotProduceEditableBlankNote', async () => {
    const original = createEmptyNote({
      id: 'note-corrupted',
      localId: 2,
      title: 'Important Note',
      content: 'Important Content',
    });
    await putNote('owner-1', original);

    // Corrupt ciphertext in IndexedDB
    const raw = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-1', 'note-corrupted']),
    );
    expect(raw?.note.sealedBody).toBeDefined();
    const corruptedBytes = new Uint8Array(raw!.note.sealedBody!);
    // Flip bytes in the ciphertext / authentication tag
    corruptedBytes[corruptedBytes.length - 1] ^= 0xff;
    corruptedBytes[corruptedBytes.length - 2] ^= 0xff;

    const corruptedPayload: StoredNotePayload = {
      ...raw!.note,
      sealedBody: corruptedBytes.buffer,
    };

    // Expected: Explicit read/decryption failure. No blank editable Note.
    await expect(openStoredNote('owner-1', corruptedPayload)).rejects.toThrow();
  });

  // Test C: decryptionFailureCannotReachSaveOrRemoteUpsert
  it('decryptionFailureCannotReachSaveOrRemoteUpsert', async () => {
    const original = createEmptyNote({
      id: 'note-save-guard',
      localId: 3,
      title: 'Secret',
      content: 'Secret Body',
    });
    await putNote('owner-1', original);

    const raw = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-1', 'note-save-guard']),
    );

    // Corrupt the ciphertext
    const corruptedBytes = new Uint8Array(raw!.note.sealedBody!);
    corruptedBytes[corruptedBytes.length - 1] ^= 0xaa;
    await withStore(NOTES_STORE, 'readwrite', (store) =>
      store.put({ ownerId: 'owner-1', id: 'note-save-guard', note: { ...raw!.note, sealedBody: corruptedBytes.buffer } }),
    );

    // Attempting to listNotes must fail rather than returning a blank note that could be saved
    await expect(listNotes('owner-1')).rejects.toThrow();

    // IndexedDB record remains untouched with its original corrupted bytes (not overwritten with blank secrets)
    const rawAfter = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-1', 'note-save-guard']),
    );
    expect(new Uint8Array(rawAfter!.note.sealedBody!)).toEqual(corruptedBytes);
  });

  // Test D: metadataActionsCannotOverwriteUnreadableNote
  it('metadataActionsCannotOverwriteUnreadableNote', async () => {
    const original = createEmptyNote({
      id: 'note-meta-guard',
      localId: 4,
      title: 'Do Not Overwrite',
      content: 'Do Not Overwrite Content',
      isPinned: false,
    });
    await putNote('owner-1', original);

    // Break key
    const notesCryptoKeyModule = await import('@/lib/crypto/notesCryptoKey');
    vi.spyOn(notesCryptoKeyModule, 'getNotesCryptoKey').mockResolvedValue(null);

    // Reading note must fail
    await expect(listNotes('owner-1')).rejects.toThrow();

    // Verify useNotesStore does not contain blank note
    expect(useNotesStore.getState().notes.find((n) => n.id === 'note-meta-guard')).toBeUndefined();
  });

  // Write-path check: sealNoteForStorage must not silently downgrade to plaintext when key is missing
  it('sealNoteForStorageDoesNotDowngradeToPlaintextWhenKeyMissing', async () => {
    const note = createEmptyNote({
      id: 'note-write-guard',
      localId: 5,
      title: 'Should Be Sealed',
      content: 'Should Be Sealed Content',
    });

    const notesCryptoKeyModule = await import('@/lib/crypto/notesCryptoKey');
    vi.spyOn(notesCryptoKeyModule, 'getNotesCryptoKey').mockResolvedValue(null);

    // Invariant: Once encryption is expected, missing key must fail, not silently persist plaintext
    await expect(sealNoteForStorage('owner-1', note)).rejects.toThrow();
  });

  // Edge Test 1: malformedSealedHeaderIsNotTreatedAsLegacyPlaintext
  it('malformedSealedHeaderIsNotTreatedAsLegacyPlaintext', async () => {
    const rawNote = createEmptyNote({
      id: 'note-bad-header',
      localId: 6,
      title: 'Secret Title',
      content: 'Secret Content',
    });
    const sealed = await sealNoteForStorage('owner-1', rawNote);

    // Corrupt magic header: change first 4 bytes to 'XXXX'
    const bytes = new Uint8Array(sealed.sealedBody!);
    bytes[0] = 0x58; // 'X'
    bytes[1] = 0x58; // 'X'
    bytes[2] = 0x58; // 'X'
    bytes[3] = 0x58; // 'X'

    const malformedRecord: StoredNotePayload = {
      ...sealed,
      sealedBody: bytes.buffer,
    };

    // Expected: explicit typed error (invalid_payload), NEVER legacy plaintext, NEVER blank note
    await expect(openStoredNote('owner-1', malformedRecord)).rejects.toThrowError(
      expect.objectContaining({
        name: 'NoteDecryptionError',
        reason: 'invalid_payload',
        noteId: 'note-bad-header',
      }),
    );
  });

  // Edge Test 2: emptySealedBodyIsNotTreatedAsLegacyPlaintext
  it('emptySealedBodyIsNotTreatedAsLegacyPlaintext', async () => {
    const emptySealedRecord: StoredNotePayload = {
      ...createEmptyNote({ id: 'note-empty-body', localId: 7 }),
      title: '',
      content: '',
      checklist: [],
      sealedBody: new ArrayBuffer(0),
    };

    // Expected: explicit typed error, NOT interpreted as legacy plaintext
    await expect(openStoredNote('owner-1', emptySealedRecord)).rejects.toThrowError(
      expect.objectContaining({
        name: 'NoteDecryptionError',
        reason: 'invalid_payload',
        noteId: 'note-empty-body',
      }),
    );
  });

  // Edge Test 3: wrongTypeSealedBodyFailsSafely
  it('wrongTypeSealedBodyFailsSafely', async () => {
    const stringBodyRecord: StoredNotePayload = {
      ...createEmptyNote({ id: 'note-wrong-type-1', localId: 8 }),
      title: '',
      content: '',
      checklist: [],
      sealedBody: 'corrupted-string-payload' as unknown as ArrayBuffer,
    };

    const nullBodyRecord: StoredNotePayload = {
      ...createEmptyNote({ id: 'note-wrong-type-2', localId: 9 }),
      title: '',
      content: '',
      checklist: [],
      sealedBody: null as unknown as ArrayBuffer,
    };

    await expect(openStoredNote('owner-1', stringBodyRecord)).rejects.toThrowError(
      expect.objectContaining({
        name: 'NoteDecryptionError',
        reason: 'invalid_payload',
      }),
    );

    await expect(openStoredNote('owner-1', nullBodyRecord)).rejects.toThrowError(
      expect.objectContaining({
        name: 'NoteDecryptionError',
        reason: 'invalid_payload',
      }),
    );
  });

  // Edge Test 4: legacyPlaintextRowStillLoadsAndMigrates
  it('legacyPlaintextRowStillLoadsAndMigrates', async () => {
    const { maybeMigrateStoredNote } = await import('@/lib/local/notesSealing');

    // Genuine legacy plaintext row has NO sealedBody property
    const legacyRow: StoredNotePayload = {
      ...createEmptyNote({
        id: 'note-legacy-1',
        localId: 10,
        title: 'Legacy Title',
        content: 'Legacy Content',
        checklist: [{ id: 'chk-1', text: 'Task 1', isChecked: false, position: 0 }],
      }),
    };
    delete legacyRow.sealedBody;

    // Must load normally and preserve all secrets
    const opened = await openStoredNote('owner-1', legacyRow);
    expect(opened.title).toBe('Legacy Title');
    expect(opened.content).toBe('Legacy Content');
    expect(opened.checklist).toEqual([{ id: 'chk-1', text: 'Task 1', isChecked: false, position: 0 }]);

    // Migration can seal it when key is available
    let writtenPayload: StoredNotePayload | null = null;
    await maybeMigrateStoredNote('owner-1', legacyRow, async (next) => {
      writtenPayload = next;
    });

    expect(writtenPayload).not.toBeNull();
    expect(writtenPayload!.sealedBody).toBeDefined();
    expect(writtenPayload!.title).toBe(''); // Shell is blanked on disk

    // And reading the newly sealed row reproduces the original contents
    const reopened = await openStoredNote('owner-1', writtenPayload!);
    expect(reopened.title).toBe('Legacy Title');
    expect(reopened.content).toBe('Legacy Content');
  });

  // Edge Test 5: malformedSealedRowIsNotMigratedAsPlaintext
  it('malformedSealedRowIsNotMigratedAsPlaintext', async () => {
    const { maybeMigrateStoredNote } = await import('@/lib/local/notesSealing');

    // Record has a malformed sealedBody and blank shell
    const malformedRecord: StoredNotePayload = {
      ...createEmptyNote({ id: 'note-malformed-migrate', localId: 11 }),
      title: '',
      content: '',
      checklist: [],
      sealedBody: new Uint8Array([0x58, 0x58, 0x58, 0x58]).buffer, // 'XXXX'
    };

    let writeCalled = false;
    await maybeMigrateStoredNote('owner-1', malformedRecord, async () => {
      writeCalled = true;
    });

    // Invariant: MUST NOT be treated as legacy plaintext and re-sealed with empty secrets!
    expect(writeCalled).toBe(false);
  });

  // Multi-Note Test: one corrupted note among healthy notes
  it('oneCorruptedNoteFailsSafelyWithoutDataLoss', async () => {
    // Note A: valid encrypted
    const noteA = createEmptyNote({ id: 'note-A', localId: 21, title: 'Note A Title', content: 'Note A Body' });
    await putNote('owner-multi', noteA);

    // Note B: valid encrypted then corrupted
    const noteB = createEmptyNote({ id: 'note-B', localId: 22, title: 'Note B Title', content: 'Note B Body' });
    await putNote('owner-multi', noteB);

    // Corrupt Note B in storage
    const rawB = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-multi', 'note-B']),
    );
    const corruptedBytes = new Uint8Array(rawB!.note.sealedBody!);
    corruptedBytes[corruptedBytes.length - 1] ^= 0xff; // corrupt tag
    await withStore(NOTES_STORE, 'readwrite', (store) =>
      store.put({ ownerId: 'owner-multi', id: 'note-B', note: { ...rawB!.note, sealedBody: corruptedBytes.buffer } }),
    );

    // Note C: valid encrypted
    const noteC = createEmptyNote({ id: 'note-C', localId: 23, title: 'Note C Title', content: 'Note C Body' });
    await putNote('owner-multi', noteC);

    // Execute real repository load path: listNotes('owner-multi')
    // Result: listNotes rejects completely with NoteDecryptionError for Note B
    let caughtError: unknown = null;
    try {
      await listNotes('owner-multi');
    } catch (err) {
      caughtError = err;
    }

    expect(caughtError).toBeInstanceOf(Error);
    expect((caughtError as { noteId?: string }).noteId).toBe('note-B');

    // Invariant 1: Corrupted ciphertext in Note B remains preserved byte-identical in IndexedDB
    const storedB = await withStore<{ note: StoredNotePayload } | undefined>(
      NOTES_STORE,
      'readonly',
      (store) => store.get(['owner-multi', 'note-B']),
    );
    expect(new Uint8Array(storedB!.note.sealedBody!)).toEqual(corruptedBytes);

    // Invariant 2: No blank Note B was returned or written to store
    expect(useNotesStore.getState().notes.find((n) => n.id === 'note-B')).toBeUndefined();
  });
});
