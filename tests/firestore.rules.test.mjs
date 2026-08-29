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
  updateDoc,
} from 'firebase/firestore';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';

const HOOK_TIMEOUT_MS = 30_000;

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
  }, HOOK_TIMEOUT_MS);

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  afterAll(async () => {
    await testEnv?.cleanup();
  }, HOOK_TIMEOUT_MS);

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

  // localId is the note's primary key and is read back as a Long on the Kotlin side, so it is
  // type-checked as strictly as every other numeric field rather than as a general `number`.
  it('rejects a fractional localId', async () => {
    const alice = authed('alice');
    await assertFails(setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ localId: 1.5 })));
  });

  // Same reasoning as localId: both are epoch millis written as integers by every client
  // (Date.now() on web, Long on Kotlin, explicit integerValue on desktop) and read back as Long,
  // so a fractional value has nowhere sensible to land.
  it('rejects a fractional timestamp', async () => {
    const alice = authed('alice');
    await assertFails(setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ timestamp: 1.5 })));
  });

  it('rejects a fractional reminderTimestamp', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ reminderTimestamp: 1.5 }))
    );
  });

  it('accepts an integer reminderTimestamp', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(noteRef(alice, 'alice', 'note-1'), validNote({ reminderTimestamp: Date.now() }))
    );
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

  it('allows desktop platform in sync metadata', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/_meta/sync'), {
        lastSyncAt: Date.now(),
        noteCount: 1,
        platform: 'desktop',
      }),
    );
  });

  it('allows desktop platform in connection meta', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/_meta/connection'), {
        connectedAt: Date.now(),
        platform: 'desktop',
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

  // Pins a known ceiling rather than a behaviour anyone wants: the rules language has no iteration,
  // so "every element of labels/checklist has the right shape" cannot be expressed. Verified
  // against the emulator — a rule using `checklist.all(i, i.text is string)` denies a write whose
  // data satisfies it, because the unknown method errors and an errored condition denies.
  //
  // Element access by index does work, and was deliberately not used: it can only cover indices
  // named literally, so it would validate element 0 and wave through elements 1..499 — validation
  // in appearance only. This test exists so the gap is recorded as a decision instead of being
  // rediscovered as a bug, and so it fails loudly if the language ever gains iteration and someone
  // closes it properly.
  it('accepts malformed elements inside labels and checklist (rules cannot iterate)', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(
        noteRef(alice, 'alice', 'note-1'),
        validNote({
          labels: [{ name: 'ok' }, { nonsense: 1 }],
          checklist: [{ text: 'ok', isChecked: false, position: 0 }, 'not even a map'],
        }),
      ),
    );
  });

  // serverUpdatedAt is the conflict-resolution clock — see NoteSyncEngine.kt / notesRepository.ts.
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

  // --- Cross-user and metadata write denials ---

  it('denies cross-user note writes and deletes', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    const bob = authed('bob');
    await assertFails(setDoc(noteRef(bob, 'alice', 'note-1'), validNote()));
    await assertFails(setDoc(noteRef(bob, 'alice', 'note-2'), validNote()));
    await assertFails(deleteDoc(noteRef(bob, 'alice', 'note-1')));
  });

  it('allows owners to write valid tombstones', async () => {
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/tombstones/note-1'), { deletedAt: Date.now() }),
    );
    await assertSucceeds(getDoc(doc(alice, 'users/alice/tombstones/note-1')));
  });

  it('rejects tombstones with unknown, missing, or mistyped fields', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(doc(alice, 'users/alice/tombstones/note-1'), { deletedAt: Date.now(), cause: 'x' }),
    );
    await assertFails(setDoc(doc(alice, 'users/alice/tombstones/note-1'), {}));
    await assertFails(
      setDoc(doc(alice, 'users/alice/tombstones/note-1'), { deletedAt: 'yesterday' }),
    );
  });

  it('rejects a tombstone whose deletedAt is not positive', async () => {
    // `pruneExpiredTombstones` deletes a tombstone once `now - deletedAt >= TOMBSTONE_TTL_MS`
    // (180 days). At 0 or negative that is true on the first sync, so the tombstone is pruned
    // before it has reached the other devices and the deleted note comes back everywhere.
    const alice = authed('alice');
    await assertFails(setDoc(doc(alice, 'users/alice/tombstones/note-1'), { deletedAt: 0 }));
    await assertFails(setDoc(doc(alice, 'users/alice/tombstones/note-1'), { deletedAt: -1 }));
  });

  it('still accepts a tombstone from a device whose clock runs ahead', async () => {
    // The bound above is deliberately one-sided. Rejecting a future deletedAt would mean the
    // deletion never propagates at all, which is worse than storing a skewed one.
    const alice = authed('alice');
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/tombstones/note-1'), {
        deletedAt: Date.now() + 7 * 24 * 60 * 60 * 1000,
      }),
    );
  });

  it('denies cross-user tombstone writes', async () => {
    const bob = authed('bob');
    await assertFails(
      setDoc(doc(bob, 'users/alice/tombstones/note-1'), { deletedAt: Date.now() }),
    );
  });

  it('denies cross-user meta writes', async () => {
    const bob = authed('bob');
    await assertFails(
      setDoc(doc(bob, 'users/alice/_meta/sync'), {
        lastSyncAt: Date.now(),
        noteCount: 0,
        platform: 'web',
      }),
    );
  });

  it('rejects invalid sync meta', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(doc(alice, 'users/alice/_meta/sync'), {
        lastSyncAt: Date.now(),
        noteCount: 1,
        platform: 'ios',
      }),
    );
    await assertFails(
      setDoc(doc(alice, 'users/alice/_meta/sync'), {
        lastSyncAt: Date.now(),
        noteCount: -1,
        platform: 'web',
      }),
    );
    await assertFails(
      setDoc(doc(alice, 'users/alice/_meta/sync'), {
        lastSyncAt: Date.now(),
        noteCount: 1,
        platform: 'web',
        role: 'admin',
      }),
    );
  });

  it('rejects non-android/web/desktop connection meta', async () => {
    const alice = authed('alice');
    await assertFails(
      setDoc(doc(alice, 'users/alice/_meta/connection'), {
        connectedAt: Date.now(),
        platform: 'ios',
      }),
    );
  });

  // --- Update paths ---

  it('allows owners to update partial note fields', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    await assertSucceeds(updateDoc(noteRef(alice, 'alice', 'note-1'), { title: 'Renamed' }));
  });

  it('rejects an update that introduces a forbidden field', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    await assertFails(updateDoc(noteRef(alice, 'alice', 'note-1'), { role: 'admin' }));
  });

  it('rejects an update that makes content oversized', async () => {
    const alice = authed('alice');
    await assertSucceeds(setDoc(noteRef(alice, 'alice', 'note-1'), validNote()));
    await assertFails(
      updateDoc(noteRef(alice, 'alice', 'note-1'), { content: 'x'.repeat(100001) }),
    );
  });
});
