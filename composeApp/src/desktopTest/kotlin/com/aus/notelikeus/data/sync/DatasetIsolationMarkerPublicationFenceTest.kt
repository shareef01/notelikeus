package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * When the in-process quarantine becomes visible, relative to the durable `Isolating` marker — R16.2.
 *
 * R16.1 made the grant primitive consult the same quarantine local commits do, so both refuse while
 * the device is between datasets. One ordering question was left open: `beginIsolation()` persists the
 * durable marker *and* publishes the in-memory mirror that *answers* that quarantine. If the durable
 * write lands first, there is a window in which the transition is already durable while every
 * in-process decision still reads a stable dataset — a matching-generation operation could mint new
 * remote-start authority during it, for a dataset the device has already left.
 *
 * These lanes pin the required order — **quarantine visible, then durable write** — with a store that
 * commits the marker and *then* blocks inside `save`, so the seam is exact and needs no sleeps: at that
 * instant the marker is durable and `beginIsolation()` has not returned. PUB-1 and PUB-2 are the
 * decisive ones; PUB-3 and PUB-4 keep the failure path honest (a failed write leaves neither a marker
 * nor a quarantine behind); PUB-5 shows every other consumer of the authority inherits the same
 * refusal without a check of its own.
 */
class DatasetIsolationMarkerPublicationFenceTest {

    @AfterTest
    fun takeTheQuarantineBackDown() {
        // The quarantine is process-wide by design — every local commit and every grant consults it —
        // so a lane that installs one has to take it back down.
        LocalCommitGate.installDatasetAuthorityQuarantine(null)
    }

    // ---- PUB-1 / PUB-2: the marker is durable, beginIsolation has not returned ----

    @Test
    fun `PUB-1 a marker that is durable but has not returned already refuses grants`() =
        runTest(timeout = TIMEOUT) {
            val store = BlockedMarkerEpochStore()
            val authority = DatasetEpochAuthority(store)
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
            val token = LocalCommitGate.capture(UID)
            val generation = LocalCommitGate.currentGeneration()
            assertNotNull(
                LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
                "precondition: a stable dataset authorizes its current token",
            )

            store.blockNextSave = true
            val isolation = async(Dispatchers.IO) { authority.beginIsolation() }
            store.markerCommitted.await()   // durable; the call has not returned

            assertTrue(store.durable.isIsolating, "precondition: the durable marker is committed")
            assertFalse(isolation.isCompleted, "precondition: the marker write is still in flight")
            assertEquals(
                generation,
                LocalCommitGate.currentGeneration(),
                "precondition: the transition has not crossed the boundary, so the generation is unchanged",
            )

            // The decisive assertion: from the instant the transition is durable, no new authority may
            // be minted — even though the generation still matches and `beginIsolation` is still open.
            assertNull(
                LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
                "a grant was minted while the transition was already durable but not yet published",
            )
            assertTrue(
                authority.isIsolationIncomplete(),
                "the in-process quarantine was not visible while the marker was being persisted",
            )

            store.releaseSave.complete(Unit)
            isolation.await()
            assertTrue(authority.isIsolationIncomplete(), "the transition did not survive the released write")
            // Serialized by the authority's own mutex, so it could not even begin during the blocked
            // write; once the mirror is published it refuses on the published quarantine.
            assertFalse(
                authority.enqueueCurrent(PendingSyncKind.UPLOAD, NOTE_ID, UID),
                "current-dataset work was queued while the device was between datasets",
            )
        }

    @Test
    fun `PUB-2 a local commit refuses at that same seam`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)
        assertEquals(
            LocalCommitResult.Applied(Unit),
            LocalCommitGate.commit(token) { },
            "precondition: a stable dataset applies a local commit",
        )

        store.blockNextSave = true
        val isolation = async(Dispatchers.IO) { authority.beginIsolation() }
        store.markerCommitted.await()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            LocalCommitGate.commit(token) { },
            "a local commit was applied while the transition was already durable",
        )
        assertEquals(
            LocalCommitResult.StaleGeneration,
            LocalCommitGate.commit(token) { },
            "the refusal at that seam depends on the pre-published quarantine, so it must be repeatable",
        )

        store.releaseSave.complete(Unit)
        isolation.await()
    }

    @Test
    fun `PUB-5 every consumer of the authority refuses at that seam`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)

        store.blockNextSave = true
        val isolation = async(Dispatchers.IO) { authority.beginIsolation() }
        store.markerCommitted.await()

        // None of these needed a check of its own: they all read the same mirror the quarantine reads,
        // so publishing it *before* the durable write is what makes them fail closed at this seam.
        assertEquals(
            LocalCommitResult.StaleGeneration,
            LocalCommitGate.commit(token) { },
            "a local commit was applied at the seam",
        )
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a remote-start grant was minted at the seam",
        )
        assertNull(
            LocalCommitGate.resolveSchedulingEpoch(token) { authority.currentEpochOrNull() },
            "the scheduler was handed an epoch to stamp work with while the device was between datasets",
        )
        assertNull(
            LocalCommitGate.resolveScheduledOrigin(
                ScheduledWorkOrigin(UID, DatasetEpoch("origin-epoch")),
                UID,
            ) { authority.currentEpochOrNull() },
            "a queued command was resolved against a dataset the device had already left",
        )

        store.releaseSave.complete(Unit)
        isolation.await()
    }

    // ---- PUB-3 / PUB-4: a failed write leaves nothing behind ----

    @Test
    fun `PUB-3 a failed marker write rolls the quarantine back`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)
        val generation = LocalCommitGate.currentGeneration()

        store.failNextSave = true
        var failure: Throwable? = null
        try {
            authority.beginIsolation()
        } catch (thrown: Throwable) {
            failure = thrown
        }

        assertNotNull(failure, "a failed marker write was reported as success")
        assertFalse(store.durable.isIsolating, "the durable store holds a transition that failed to persist")
        assertEquals(
            listOf(false),
            store.writes.map { it.isIsolating },
            "the store must have persisted the initial epoch and nothing else",
        )
        assertFalse(
            authority.isIsolationIncomplete(),
            "the pre-published quarantine survived a failed marker write, refusing a dataset that is still current",
        )
        assertEquals(
            generation,
            LocalCommitGate.currentGeneration(),
            "a failed marker write moved the generation",
        )
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a failed marker write left the current dataset's authority refused",
        )
        assertEquals(
            LocalCommitResult.Applied(Unit),
            LocalCommitGate.commit(token) { },
            "a failed marker write left local commits refused",
        )
    }

    @Test
    fun `PUB-4 a retry after a failed write still reaches the replacement dataset`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val oldToken = LocalCommitGate.capture(UID)

        store.failNextSave = true
        var failure: Throwable? = null
        try {
            authority.beginIsolation()
        } catch (thrown: Throwable) {
            failure = thrown
        }
        assertNotNull(failure, "precondition: the first attempt failed")

        // The retry: same entry point, working store, and then the real recovery sequence — the
        // boundary is crossed and the transition completed inside it.
        authority.beginIsolation()
        assertTrue(authority.isIsolationIncomplete(), "the retry did not reach the transition")
        LocalCommitGate.isolate { authority.completeIsolation() }

        assertFalse(authority.isIsolationIncomplete(), "the recovery did not complete the transition")
        assertFalse(store.durable.isIsolating, "the durable store still holds an in-flight transition")
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(oldToken, MUTATION),
            "the replaced dataset's token was authorized after recovery",
        )
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(LocalCommitGate.capture(UID), MUTATION),
            "recovery left remote-start authority permanently disabled",
        )
    }

    // ---- PUBF-1..PUBF-8: what a *failed* marker write may leave behind ----

    @Test
    fun `PUBF-1 a write that fails before committing still restores the current dataset`() =
        runTest(timeout = TIMEOUT) {
            val store = BlockedMarkerEpochStore()
            val authority = DatasetEpochAuthority(store)
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
            val token = LocalCommitGate.capture(UID)
            val generation = LocalCommitGate.currentGeneration()
            store.failNextSave = true

            val attempt = async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

            assertTrue(attempt.isFailure, "a failed marker write was reported as success")
            assertTrue(
                store.writes.isNotEmpty() && store.writes.all { !it.isIsolating },
                "precondition: a marker write never reached the durable backing",
            )
            assertFalse(store.durable.isIsolating, "the durable store holds a transition that failed to persist")
            assertFalse(
                authority.isIsolationIncomplete(),
                "the dataset that is still current stayed quarantined after a write that never committed",
            )
            assertEquals(generation, LocalCommitGate.currentGeneration(), "a failed write moved the generation")
            assertNotNull(
                LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
                "a pre-commit failure left the current dataset's authority refused",
            )
            assertEquals(
                LocalCommitResult.Applied(Unit),
                LocalCommitGate.commit(token) { },
                "a pre-commit failure left local commits refused",
            )
        }

    @Test
    fun `PUBF-2 a marker that commits and then cancels stays quarantined`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)
        store.failAfterCommit = CancellationException("cancelled after the durable write")

        val attempt = async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

        assertTrue(attempt.isFailure, "the failure was not reported")
        assertTrue(store.durable.isIsolating, "precondition: the marker is durable")
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a grant was minted after a marker write that was durable but reported as failed",
        )
        assertTrue(
            authority.isIsolationIncomplete(),
            "the quarantine was rolled back although the durable marker exists",
        )
        assertEquals(
            LocalCommitResult.StaleGeneration,
            LocalCommitGate.commit(token) { },
            "a local commit was applied although the durable marker exists",
        )
    }

    @Test
    fun `PUBF-3 a marker that commits and then fails stays quarantined`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)
        store.failAfterCommit = java.io.IOException("write reported failure after the durable commit")

        val attempt = async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

        assertTrue(attempt.isFailure, "the failure was not reported")
        assertTrue(store.durable.isIsolating, "precondition: the marker is durable")
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a grant was minted after an ambiguous marker write",
        )
        assertTrue(
            authority.isIsolationIncomplete(),
            "the quarantine was rolled back although the durable marker exists",
        )
        assertEquals(
            LocalCommitResult.StaleGeneration,
            LocalCommitGate.commit(token) { },
            "a local commit was applied although the durable marker exists",
        )
    }

    @Test
    fun `PUBF-4 an ambiguous marker failure leaves no remote-start authority`() = runTest(timeout = TIMEOUT) {
        val failures = listOf(
            "cancel" to CancellationException("cancelled after the durable write"),
            "throw" to java.io.IOException("failed after the durable commit"),
        )
        for ((label, failure) in failures) {
            val store = BlockedMarkerEpochStore()
            val authority = DatasetEpochAuthority(store)
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
            val token = LocalCommitGate.capture(UID)
            store.failAfterCommit = failure

            async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

            assertNull(
                LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
                "a grant was minted after an ambiguous marker failure ($label)",
            )
        }
    }

    @Test
    fun `PUBF-5 an ambiguous marker failure refuses local commits`() = runTest(timeout = TIMEOUT) {
        val failures = listOf(
            "cancel" to CancellationException("cancelled after the durable write"),
            "throw" to java.io.IOException("failed after the durable commit"),
        )
        for ((label, failure) in failures) {
            val store = BlockedMarkerEpochStore()
            val authority = DatasetEpochAuthority(store)
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
            val token = LocalCommitGate.capture(UID)
            store.failAfterCommit = failure

            async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

            assertEquals(
                LocalCommitResult.StaleGeneration,
                LocalCommitGate.commit(token) { },
                "a local commit was applied after an ambiguous marker failure ($label)",
            )
        }
    }

    @Test
    fun `PUBF-6 a restart after an ambiguous committed marker reads isolating`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val first = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { first.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)
        store.failAfterCommit = CancellationException("cancelled after the durable write")
        async(Dispatchers.IO) { runCatching { first.beginIsolation() } }.await()
        assertTrue(store.durable.isIsolating, "precondition: the marker is durable")

        // The next process, over the same backing.
        val restarted = DatasetEpochAuthority(store)
        val authority = restarted.awaitAuthority()
        LocalCommitGate.installDatasetAuthorityQuarantine { restarted.isIsolationIncomplete() }

        assertTrue(authority is DatasetAuthority.Isolating, "a restart did not read the durable transition")
        assertNull(
            restarted.currentEpochOrNull(),
            "a restart handed out an epoch while the device was between datasets",
        )
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "the old dataset's token was authorized after a restart",
        )
    }

    @Test
    fun `PUBF-7 recovery after an ambiguous failure reaches the new dataset`() = runTest(timeout = TIMEOUT) {
        val store = BlockedMarkerEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val oldToken = LocalCommitGate.capture(UID)
        store.failAfterCommit = CancellationException("cancelled after the durable write")
        async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

        val marked = store.durable.isolationTarget
        assertNotNull(marked, "precondition: the durable marker records its target")

        // The retry must reuse the target the durable record already holds: replacing it would launder
        // the epoch the interrupted transition chose.
        val retried = authority.beginIsolation()
        assertEquals(marked, retried, "the retry laundered the target epoch the durable record already had")

        LocalCommitGate.isolate { authority.completeIsolation() }

        assertFalse(authority.isIsolationIncomplete(), "the recovery did not complete the transition")
        assertFalse(store.durable.isIsolating, "the durable store still holds an in-flight transition")
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(oldToken, MUTATION),
            "the replaced dataset's token was authorized after recovery",
        )
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(LocalCommitGate.capture(UID), MUTATION),
            "recovery left remote-start authority permanently disabled",
        )
    }

    @Test
    fun `PUBF-8 an unreadable durable record after an ambiguous write stays fail-closed`() =
        runTest(timeout = TIMEOUT) {
            val store = BlockedMarkerEpochStore()
            val authority = DatasetEpochAuthority(store)
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
            val token = LocalCommitGate.capture(UID)
            store.failAfterCommit = java.io.IOException("failed after the durable commit")
            store.failReloadAfterCommit = true

            val attempt = async(Dispatchers.IO) { runCatching { authority.beginIsolation() } }.await()

            assertTrue(attempt.isFailure, "the failure was not reported")
            assertNull(
                LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
                "a grant was minted although nothing could be established about the durable record",
            )
            assertTrue(
                authority.isIsolationIncomplete(),
                "the authority returned to a stable dataset from memory alone",
            )
        }

    private companion object {
        const val UID = "same-user"
        const val NOTE_ID = 42L
        const val MUTATION = "writeTombstone"

        val TIMEOUT = 60.seconds
    }
}
