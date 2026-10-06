package com.aus.notelikeus.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * F-8: an accepted destructive cloud-wipe request is not forgotten.
 *
 * The product accepts "sign out and delete my cloud notes". The wipe can fail, be interrupted, or have its
 * result be ambiguous, and local account isolation proceeds regardless — it must, because keeping local
 * account data because the network refused a delete would be the wrong trade. What must not happen is the
 * *request* disappearing: the durable record is written before any destructive work, survives everything
 * that follows, and is cleared only when completion is authoritative.
 */
class PendingCloudWipeIntentTest {

    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private class RecordingWipe {
        var runs = 0
        var behavior: () -> Unit = { }
        val invoked: Boolean get() = runs > 0
    }

    private fun wipe(recorder: RecordingWipe): suspend () -> Unit = {
        recorder.runs += 1
        recorder.behavior()
    }

    /** WIPE-1: a failed remote wipe keeps the accepted intent. */
    @Test
    fun `WIPE-1 a failed wipe does not erase the accepted request`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe().apply { behavior = { error("network down") } }

        val outcome = CloudWipeCoordinator(store).requestWipe(owner, 1L, wipe(recorder))

        assertTrue(outcome is CloudWipeOutcome.Failed, "a failed wipe reported $outcome")
        assertEquals(owner, store.find(owner)?.ownerUid, "the accepted request was forgotten")
    }

    /** WIPE-2: the record is in the store, not in the coordinator, so a restart keeps it. */
    @Test
    fun `WIPE-2 a restart retains the intent`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe().apply { behavior = { error("interrupted") } }
        CloudWipeCoordinator(store).requestWipe(owner, 1L, wipe(recorder))

        val afterRestart = CloudWipeCoordinator(store)          // a fresh process over the same store
        recorder.behavior = { }
        val outcome = afterRestart.resumeIfPending(owner, wipe(recorder))

        assertTrue(outcome is CloudWipeOutcome.Completed, "the restarted session did not finish it: $outcome")
        assertNull(store.find(owner), "the completed request was not cleared")
    }

    /** WIPE-3: signing back in as the same account resumes it. */
    @Test
    fun `WIPE-3 the same account resumes the pending wipe`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe().apply { behavior = { error("offline") } }
        val coordinator = CloudWipeCoordinator(store)
        coordinator.requestWipe(owner, 1L, wipe(recorder))

        recorder.behavior = { }
        val resumed = coordinator.resumeIfPending(owner, wipe(recorder))

        assertTrue(resumed is CloudWipeOutcome.Completed, "resume reported $resumed")
        assertEquals(2, recorder.runs, "the wipe was not retried exactly once")
    }

    /** WIPE-4: another account cannot execute it, and cannot erase it either. */
    @Test
    fun `WIPE-4 a different account neither runs nor clears a pending wipe`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe().apply { behavior = { error("offline") } }
        val coordinator = CloudWipeCoordinator(store)
        coordinator.requestWipe(owner, 1L, wipe(recorder))
        val runsBefore = recorder.runs

        val outcome = coordinator.resumeIfPending(other, wipe(recorder))

        assertNull(outcome, "another account executed a request that is not its own: $outcome")
        assertEquals(runsBefore, recorder.runs, "another account's session invoked the owner's wipe")
        assertEquals(owner, store.find(owner)?.ownerUid, "another account erased the pending request")
    }

    /** WIPE-5: a partially applied wipe is finished by the retry. */
    @Test
    fun `WIPE-5 a partial wipe is completed by the retry`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val remaining = mutableListOf("note-1", "note-2")
        val recorder = RecordingWipe().apply {
            behavior = {
                remaining.removeAt(0)                            // the server deleted something, then failed
                error("connection reset after partial progress")
            }
        }
        val coordinator = CloudWipeCoordinator(store)
        coordinator.requestWipe(owner, 1L, wipe(recorder))
        assertEquals(listOf("note-2"), remaining, "precondition: the first attempt made partial progress")

        recorder.behavior = { remaining.clear() }                // the retry is idempotent by construction
        val resumed = coordinator.resumeIfPending(owner, wipe(recorder))

        assertTrue(resumed is CloudWipeOutcome.Completed, "the retry did not complete: $resumed")
        assertTrue(remaining.isEmpty(), "the retry did not finish the wipe")
        assertNull(store.find(owner), "the request was not cleared after completion")
    }

    /** WIPE-6: ambiguous completion — the server wiped, the client never heard. */
    @Test
    fun `WIPE-6 an ambiguous completion stays pending then resolves`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val server = mutableListOf("note-1")
        val recorder = RecordingWipe().apply {
            behavior = {
                server.clear()                                   // the wipe happened on the server…
                error("timeout waiting for the response")        // …and the answer never arrived
            }
        }
        val coordinator = CloudWipeCoordinator(store)
        val first = coordinator.requestWipe(owner, 1L, wipe(recorder))

        assertTrue(first is CloudWipeOutcome.Failed, "an unacknowledged wipe reported $first")
        assertNotNull(store.find(owner), "an ambiguous completion dropped the request")

        recorder.behavior = { }
        val second = coordinator.resumeIfPending(owner, wipe(recorder))
        assertTrue(second is CloudWipeOutcome.Completed, "the retry did not resolve: $second")
        assertNull(store.find(owner), "the request survived an authoritative completion")
    }

    /** WIPE-7: authoritative success clears it. */
    @Test
    fun `WIPE-7 an authoritative success clears the request`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val outcome = CloudWipeCoordinator(store).requestWipe(owner, 1L, wipe(RecordingWipe()))

        assertTrue(outcome is CloudWipeOutcome.Completed, "the wipe reported $outcome")
        assertNull(store.find(owner), "the completed request was left behind")
    }

    /** WIPE-8: local account isolation does not touch the request. */
    @Test
    fun `WIPE-8 local isolation does not clear the pending request`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe().apply { behavior = { error("offline") } }
        CloudWipeCoordinator(store).requestWipe(owner, 1L, wipe(recorder))

        LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore().apply { setLastMergedUserId(owner) },
            RecordingSyncCoordinator(),
        ).isolate()

        assertEquals(owner, store.find(owner)?.ownerUid, "local isolation erased the accepted request")
    }

    /** WIPE-9: an ordinary sign-out creates nothing destructive. */
    @Test
    fun `WIPE-9 an ordinary sign-out creates no destructive intent`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()

        LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore().apply { setLastMergedUserId(owner) },
            RecordingSyncCoordinator(),
        ).isolate()

        assertNull(store.find(owner), "an ordinary sign-out left a destructive request behind")
    }

    /** WIPE-10: with nothing pending, an account operation is untouched. */
    @Test
    fun `WIPE-10 no pending request means no work`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe()

        val outcome = CloudWipeCoordinator(store).resumeIfPending(owner, wipe(recorder))

        assertNull(outcome, "a resume ran with nothing pending")
        assertFalse(recorder.invoked, "a resume invoked the wipe with nothing pending")
    }

    /** WIPE-11/§24: no durable record means no destructive work at all. */
    @Test
    fun `WIPE-11 a store that cannot record prevents the wipe`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore().apply { failWrites = true }
        val recorder = RecordingWipe()

        val outcome = CloudWipeCoordinator(store).requestWipe(owner, 1L, wipe(recorder))

        assertTrue(outcome is CloudWipeOutcome.NotStarted, "a wipe with no record reported $outcome")
        assertFalse(recorder.invoked, "destructive work started without a durable record")
    }

    /** WIPE-12/§25: a wipe that succeeded but could not be recorded as cleared stays retryable. */
    @Test
    fun `WIPE-12 a wipe whose record cannot be cleared stays harmlessly pending`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore().apply { failClear = true }
        val recorder = RecordingWipe()
        val coordinator = CloudWipeCoordinator(store)

        val outcome = coordinator.requestWipe(owner, 1L, wipe(recorder))

        assertTrue(outcome is CloudWipeOutcome.CompletedButStillPending, "reported $outcome")
        assertNotNull(store.find(owner), "a completed wipe lost its record instead of staying pending")

        store.failClear = false
        val settled = coordinator.resumeIfPending(owner, wipe(recorder))
        assertTrue(settled is CloudWipeOutcome.Completed, "the harmless retry did not settle: $settled")
        assertNull(store.find(owner), "the record was not cleared once the store recovered")
    }


    /** WIPE-13: one account's request can never overwrite another account's. */
    @Test
    fun `WIPE-13 another account's request cannot overwrite a pending owner`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recruiter = RecordingWipe().apply { behavior = { error("offline") } }
        val coordinator = CloudWipeCoordinator(store)
        coordinator.requestWipe(owner, 1L, wipe(recruiter))

        val otherWipe = RecordingWipe().apply { behavior = { error("offline") } }
        val outcome = coordinator.requestWipe(other, 2L, wipe(otherWipe))

        assertTrue(outcome is CloudWipeOutcome.Failed, "the second account's request reported $outcome")
        assertEquals(1, otherWipe.runs, "the second account's own wipe did not run")
        assertEquals(owner, store.find(owner)?.ownerUid, "the first account's accepted request was forgotten")
        assertEquals(other, store.find(other)?.ownerUid, "the second account's request was not recorded")
        assertEquals(
            setOf(owner, other),
            store.read().map { it.ownerUid }.toSet(),
            "one account's request displaced the other's",
        )
    }

    /** WIPE-14: a repeat request from the same account keeps its original obligation. */
    @Test
    fun `WIPE-14 a repeat request from the same account is idempotent`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val recorder = RecordingWipe().apply { behavior = { error("offline") } }
        val coordinator = CloudWipeCoordinator(store)
        coordinator.requestWipe(owner, 1L, wipe(recorder))
        val original = store.find(owner)

        coordinator.requestWipe(owner, 999L, wipe(recorder))

        assertEquals(2, recorder.runs, "the repeat request did not retry the owed wipe")
        assertEquals(original, store.find(owner), "the repeat request rewrote the pending obligation")
        assertEquals(1L, store.find(owner)?.requestedAtMillis, "the original request time was lost")
    }

    /** WIPE-15/§13: a completion clears only the account that completed. */
    @Test
    fun `WIPE-15 a completion cannot clear another owner's intent`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val failing = RecordingWipe().apply { behavior = { error("offline") } }
        val coordinator = CloudWipeCoordinator(store)
        coordinator.requestWipe(owner, 1L, wipe(failing))       // A stays owed
        coordinator.requestWipe(other, 2L, wipe(RecordingWipe()))  // B is recorded and completes

        assertEquals(owner, store.find(owner)?.ownerUid, "B's completion erased A's obligation")
        assertNull(store.find(other), "B's own record was not cleared by its completion")
    }

    /** WIPE-16/§6: two accepted requests cannot race away one another. */
    @Test
    fun `WIPE-16 concurrent accepted requests cannot lose an intent`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryPendingCloudWipeIntentStore()
        val failing = RecordingWipe().apply { behavior = { error("offline") } }
        val coordinator = CloudWipeCoordinator(store)

        val both = listOf(
            async(Dispatchers.Default) { coordinator.requestWipe(owner, 1L, wipe(failing)) },
            async(Dispatchers.Default) { coordinator.requestWipe(other, 2L, wipe(failing)) },
        ).awaitAll()

        assertTrue(both.all { it is CloudWipeOutcome.Failed }, "an accepted request reported $both")
        assertEquals(
            setOf(owner, other),
            store.read().map { it.ownerUid }.toSet(),
            "a concurrent request was lost: ${store.read().map { it.ownerUid }}",
        )
    }

    private companion object {
        val TIMEOUT = 60.seconds
    }
}
