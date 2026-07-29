import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  deleteDoc,
  doc,
  getDoc,
  serverTimestamp,
  setDoc,
  Timestamp,
} from 'firebase/firestore';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';

const PROJECT_ID = 'notelikeus-rules-test';
const RULES_PATH = resolve(process.cwd(), 'firestore.rules');

let testEnv;

function authed(userId) {
  return testEnv.authenticatedContext(userId, { sub: userId }).firestore();
}

function noteRef(db, userId, noteId) {
  return doc(db, `users/${userId}/notes/${noteId}`);
}

function validNote(overrides = {}) {
  return {
    localId: 1,
    title: 'Trip',
    content: 'Pack bags',
    timestamp: Date.now(),
    color: 0,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    position: 0,
    isLocked: false,
    labels: [],
    checklist: [],
    ...overrides,
  };
}

describe('firestore.rules', () => {
  beforeAll(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules: readFileSync(RULES_PATH, 'utf8'),
      },
    });
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  afterAll(async () => {
    await testEnv.cleanup();
  });

  it('denies unauthenticated reads', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(noteRef(db, 'alice', 'note-1')));
  });

  it('denies cross-user access', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    const bob = authed('bob');
    await assertFails(getDoc(noteRef(bob, 'alice', 'note-1')));
  });

  it('allows owners to create and read valid notes', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    await assertSucceeds(getDoc(noteRef(alice, 'alice', 'note-1')));
  });

  it('rejects oversized note content', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ content: 'x'.repeat(500001) })),
    );
  });

  // Note locking was removed. Clients that predate the removal still send isLocked, so it must
  // stay accepted; clients that postdate it omit the field entirely, which must also be accepted.
  it('still accepts isLocked from clients that predate its removal', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(
        noteRef(alice, 'alice', 'note-1'),
        validNote({ isLocked: true, title: 'Secret', content: 'Hidden' }),
      ),
    );
  });

  it('accepts a note with no isLocked field at all', async () => {
    const alice = authed('alice');
    const { isLocked: _dropped, ...withoutLock } = validNote();
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), withoutLock));
  });

  it('rejects a non-boolean isLocked', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ isLocked: 'yes' })),
    );
  });

  it('allows owners to delete their notes', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    await assertSucceeds(deleteDoc(noteRef(alice, 'alice', 'note-1')));
  });

  it('allows owners to write sync metadata', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/_meta/sync'), {
        lastSyncAt: Date.now(),
        noteCount: 1,
        platform: 'web',
      }),
    );
  });

  it('allows owners to write connection ping', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/_meta/connection'), {
        connectedAt: Date.now(),
        platform: 'android',
      }),
    );
  });

  it('rejects notes with unknown extra fields', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ role: 'admin' })),
    );
  });

  it('accepts null for optional fields', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ reminderTimestamp: null })),
    );
  });

  it('rejects null for required color', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ color: null })),
    );
  });

  it('rejects oversized labels array', async () => {
    const alice = authed('alice');
    const hugeLabels = Array.from({ length: 501 }, (_, i) => ({ name: `label-${i}` }));
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ labels: hugeLabels })),
    );
  });

  // serverUpdatedAt is the conflict-resolution clock — see FirebaseNoteSync.kt / notesRepository.ts.
  // It has to be genuinely server-assigned, or a malicious/modified client could forge it to win
  // every sync conflict.
  it('accepts a note with no serverUpdatedAt field at all', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
  });

  it('accepts serverUpdatedAt when it resolves to the server timestamp sentinel', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(
        noteRef(alice, 'alice', 'note-1'),
        validNote({ serverUpdatedAt: serverTimestamp() }),
      ),
    );
  });

  it('rejects a client-forged serverUpdatedAt that is not the server commit time', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(
        noteRef(alice, 'alice', 'note-1'),
        validNote({ serverUpdatedAt: Timestamp.fromMillis(1) }),
      ),
    );
  });

  it('rejects a non-timestamp serverUpdatedAt', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ serverUpdatedAt: Date.now() })),
    );
  });
});
