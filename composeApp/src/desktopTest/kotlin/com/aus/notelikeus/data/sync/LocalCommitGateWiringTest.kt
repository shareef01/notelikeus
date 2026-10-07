package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Proves the two halves of the account-isolation boundary actually share one lock, and that the two
 * legal orderings around it hold.
 *
 * `LocalCommitGate` is what makes D8's fix real: the engine's device-local commits and
 * `LocalAccountIsolator.isolate()` must be mutually exclusive, or a sign-in can interleave between
 * the session check and the write. That guarantee is only as good as the wiring — a future change
 * from the `object`-default to an injected `Mutex()` would silently split the lock in two and
 * reintroduce D8 with every test still green. The first test pins the wiring itself: it holds the
 * process-wide lock and asserts that a **default-constructed** engine (the production shape) blocks
 * on it rather than completing.
 */
class LocalCommitGateWiringTest {

    @Test
    fun `a default-constructed engine commits through the process-wide gate`() =
        runTest(timeout = TIMEOUT) {
            val stateStore = FakeNoteSyncStateStore().apply { setLastMergedUserId(UID) }
            val transportReached = CompletableDeferred<Unit>()
            val releaseTransport = CompletableDeferred<Unit>()
            val transport = object : TestIdentityBoundNoteTransportAdapter(), CloudNoteTransport by FakeCloudNoteTransport() {
                // The first remote call of reconcileUploads. It is now reached *after* the operation's
                // identity capture (R13A), which validates the originating generation under the same
                // gate; the local commit this test is about follows it.
                override suspend fun fetchTombstones(uid: String): Map<Long, Long> {
                    transportReached.complete(Unit)
                    releaseTransport.await()
                    return emptyMap()
                }
            }
            val engine = NoteSyncEngine(
                transport = transport,
                remoteIdentityProvider = testRemoteIdentityProvider { UID },
                noteDao = FakeNoteDao(),
                labelDao = FakeLabelDao(),
                syncStateStore = stateStore,
                uidProvider = { Result.success(UID) },
                platform = "desktop",
            )

            // Park the engine inside its first remote call — past identity capture, before the
            // post-remote local commit — and take the process-wide gate only then. So what blocks
            // below is the engine's own gated commit, not a lock the test got in ahead of.
            val gate = LocalCommitGate.mutex
            val finished = CompletableDeferred<Unit>()
            launch {
                engine.reconcileUploads()
                finished.complete(Unit)
            }
            transportReached.await()
            gate.lock()
            try {
                releaseTransport.complete(Unit)
                // Every chance to get past the lock, short of actually taking it.
                repeat(20) { yield() }

                assertFalse(
                    finished.isCompleted,
                    "the engine did not block on the process-wide gate, so it is using a private " +
                        "Mutex — the account-isolation boundary is split (D8 returns silently)",
                )
            } finally {
                gate.unlock()
            }

            // Bounded by runTest's timeout: if the openers ever deadlock, this fails rather than hangs.
            finished.await()
            assertTrue(stateStore.lastReconciledAt() > 0L, "the commit never happened at all")
        }

    @Test
    fun `isolation that takes the gate first makes the stale commit a no-op`() =
        runTest(timeout = TIMEOUT) {
            val noteDao = FakeNoteDao()
            noteDao.insertNote(
                Note(
                    id = NOTE_ID,
                    title = "A's note",
                    content = "A's body",
                    timestamp = 1_000L,
                    color = 0,
                ).toNoteEntity()
            )
            val stateStore = FakeNoteSyncStateStore()

            val uploadReached = CompletableDeferred<Unit>()
            val releaseUpload = CompletableDeferred<Unit>()
            val transport = object : TestIdentityBoundNoteTransportAdapter(), CloudNoteTransport by FakeCloudNoteTransport() {
                override suspend fun putNotes(
                    uid: String,
                    notes: List<Note>,
                ): Map<Long, CloudNoteTransport.PutResult> {
                    uploadReached.complete(Unit)
                    releaseUpload.await()
                    return FakeCloudNoteTransport().putNotes(uid, notes)
                }
            }
            var currentUid = UID_A
            val engine = NoteSyncEngine(
                transport = transport,
                remoteIdentityProvider = testRemoteIdentityProvider { currentUid },
                noteDao = noteDao,
                labelDao = FakeLabelDao(),
                syncStateStore = stateStore,
                uidProvider = { Result.success(currentUid) },
                platform = "desktop",
            )

            val upload = async { engine.uploadNote(NOTE_ID) }
            uploadReached.await()

            // Ordering B: isolation takes the gate first, completes the transition, releases. In
            // production that is `LocalAccountIsolator.isolate()`: the account-owned wipe happens
            // inside the same held section that **moves the generation**, and that movement is what
            // refuses the stale commit below — a uid comparison could not, since a cancelled or
            // delayed operation may still be holding the previous account's uid.
            LocalCommitGate.isolate {
                noteDao.deleteAllNotes()
                stateStore.clear()
                currentUid = UID_B
                noteDao.insertNote(
                    Note(
                        id = NOTE_ID,
                        title = "B's note",
                        content = "B's body",
                        timestamp = 9_000L,
                        color = 0,
                    ).toNoteEntity()
                )
                stateStore.updateKnownServerRevision(NOTE_ID, B_REVISION)
            }

            releaseUpload.complete(Unit)
            upload.await()

            // A refused local commit is not a generic sync failure (Phase 3D.2A): the upload itself
            // did reach the server, and only its local continuation was discarded. What must hold is
            // that the record of it never reached B's state — asserted here.
            val stored = noteDao.getNoteById(NOTE_ID)
            assertEquals("B's note", stored?.note?.title, "the stale commit mutated B's row")
            assertEquals(
                B_REVISION,
                stateStore.knownServerRevisionById()[NOTE_ID],
                "the stale commit mutated B's sync state",
            )
        }

    private companion object {
        const val UID = "uid"
        const val UID_A = "uid-A"
        const val UID_B = "uid-B"
        const val NOTE_ID = 42L
        const val B_REVISION = 999L
        val TIMEOUT = 60.seconds
    }
}
