package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.sync.NoopSyncCoordinator
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Focused tests for the account-generation commit primitive.
 *
 * `LocalCommitGate` is a process-wide `object`, so the generation carries over between tests in a JVM.
 * **Every assertion here is therefore relative** — captured-before vs captured-after, or an event
 * order — never an absolute generation value.
 *
 * No sleeps and no yield-counts: the ordering tests synchronize on [CompletableDeferred] signals
 * raised from inside the gate's critical sections, so the interleaving is exact.
 */
class LocalCommitGatePhase1Test {

    @Test
    fun `Gate-1 a token from the current generation commits`() = runTest(timeout = TIMEOUT) {
        var ran = false
        val token = LocalCommitGate.capture(UID_A)

        val result = LocalCommitGate.commit(token) {
            ran = true
            "ok"
        }

        assertTrue(ran, "the mutation block did not run for a current-generation token")
        assertEquals(LocalCommitResult.Applied("ok"), result)
    }

    @Test
    fun `Gate-2 isolation invalidates a token captured before it`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID_A)

        LocalCommitGate.isolate { }

        var ran = false
        val result = LocalCommitGate.commit(token) { ran = true }

        assertIs<LocalCommitResult.StaleGeneration>(result)
        assertFalse(ran, "the stale mutation block ran after isolation")
    }

    @Test
    fun `Gate-3 same uid in a new generation does not resurrect the old token`() =
        runTest(timeout = TIMEOUT) {
            val old = LocalCommitGate.capture(UID_A)
            LocalCommitGate.isolate { }
            val fresh = LocalCommitGate.capture(UID_A)

            assertEquals(
                old.initiatingUid,
                fresh.initiatingUid,
                "precondition: the uid is identical on both sides of the boundary",
            )
            assertTrue(
                fresh.generation > old.generation,
                "precondition: the generation moved even though the uid did not",
            )

            var ranOld = false
            assertIs<LocalCommitResult.StaleGeneration>(
                LocalCommitGate.commit(old) { ranOld = true },
            )
            assertFalse(ranOld, "a previous-incarnation token committed into the new one")

            var ranNew = false
            assertIs<LocalCommitResult.Applied<Unit>>(
                LocalCommitGate.commit(fresh) { ranNew = true },
            )
            assertTrue(ranNew, "the new incarnation's own token was refused")
        }

    @Test
    fun `Gate-4 a commit that holds the gate completes before isolation`() =
        runTest(timeout = TIMEOUT) {
            val events = mutableListOf<String>()
            val insideCommit = CompletableDeferred<Unit>()
            val releaseCommit = CompletableDeferred<Unit>()
            val token = LocalCommitGate.capture(UID_A)

            val committing = launch {
                LocalCommitGate.commit(token) {
                    events += "commit"
                    insideCommit.complete(Unit)
                    releaseCommit.await()
                }
                events += "commit-done"
            }
            insideCommit.await()

            val isolating = launch {
                LocalCommitGate.isolate { events += "isolate" }
            }

            releaseCommit.complete(Unit)
            committing.join()
            isolating.join()

            assertEquals(
                listOf("commit", "commit-done", "isolate"),
                events,
                "isolation entered the gate while a commit held it",
            )
        }

    @Test
    fun `Gate-5 isolation that holds the gate refuses a stale commit`() =
        runTest(timeout = TIMEOUT) {
            val events = mutableListOf<String>()
            val insideIsolate = CompletableDeferred<Unit>()
            val releaseIsolate = CompletableDeferred<Unit>()
            val staleToken = LocalCommitGate.capture(UID_A)

            val isolating = launch {
                LocalCommitGate.isolate {
                    events += "isolate"
                    insideIsolate.complete(Unit)
                    releaseIsolate.await()
                }
            }
            // The generation is already bumped and the gate is still held.
            insideIsolate.await()

            val staleCommit = async { LocalCommitGate.commit(staleToken) { events += "MUTATION" } }

            releaseIsolate.complete(Unit)
            isolating.join()
            val result = staleCommit.await()

            assertIs<LocalCommitResult.StaleGeneration>(result)
            assertFalse(
                "MUTATION" in events,
                "the stale commit mutated post-isolation state",
            )
        }

    @Test
    fun `Gate-6 guest adoption into the first account is not an account boundary`() =
        runTest(timeout = TIMEOUT) {
            // lastMergedUserId() == null: no different previous account, so no isolation is due and
            // the guest library is adopted by the first signed-in account.
            val stateStore = FakeNoteSyncStateStore()
            val isolator = LocalAccountIsolator(
                noteRepository = FakeNoteRepository(),
                syncStateStore = stateStore,
                syncCoordinator = NoopSyncCoordinator(),
            )

            val guestToken = LocalCommitGate.capture(initiatingUid = null)
            isolator.isolateIfAccountChanged(FIRST_ACCOUNT_UID)

            assertEquals(
                guestToken.generation,
                LocalCommitGate.currentGeneration(),
                "non-isolating guest adoption created an account boundary; an edit captured moments " +
                    "earlier would be stranded",
            )

            var ran = false
            assertIs<LocalCommitResult.Applied<Unit>>(
                LocalCommitGate.commit(guestToken) { ran = true },
                "a guest edit was refused by a boundary that never happened",
            )
            assertTrue(ran)
        }

    @Test
    fun `Gate-7 capture observes the generation published by a completed isolation`() =
        runTest(timeout = TIMEOUT) {
            val before = LocalCommitGate.capture(UID_A)

            LocalCommitGate.isolate { }

            val after = LocalCommitGate.capture(UID_A)

            assertEquals(
                before.generation + 1,
                after.generation,
                "capture did not observe the generation a completed isolation published",
            )
            assertEquals(
                after.generation,
                LocalCommitGate.currentGeneration(),
                "capture and currentGeneration disagree on the published generation",
            )
        }

    private companion object {
        const val UID_A = "uid-A"
        const val FIRST_ACCOUNT_UID = "first-account"
        val TIMEOUT = 60.seconds
    }
}
