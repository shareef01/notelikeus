package com.aus.notelikeus.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A cloud read that comes back *short* must not be reconciled as a set of deletions.
 *
 * [SuspectEmptyCloudException] only fires on an answer that is entirely empty. A snapshot that
 * lost some of its rows — a truncated aggregate, a page that never arrived, a row whose id could
 * not be parsed — is not empty, so it walks straight into the reconciliation loops, where every
 * previously-known id missing from it is read as deleted-elsewhere: the local copy is deleted, a
 * tombstone is written, and that deletion is then propagated to every other device.
 *
 * Nothing in the payload distinguishes "these notes were deleted" from "these notes did not
 * arrive". Only a count the server produced separately can, which is what
 * [CloudNoteSnapshot.authoritativeNoteCount] carries.
 */
class TruncatedCloudSnapshotTest {

    /** Uploads, downloads, and leaves the harness with a known-good cloud and known-id set. */
    private suspend fun syncedHarness(): Pair<SyncChaosHarness, List<Long>> {
        val h = SyncChaosHarness()
        val ids = listOf(
            h.createLocalNote("first"),
            h.createLocalNote("second"),
            h.createLocalNote("third"),
        )
        h.engine.uploadAllNotes()
        h.tick()
        assertTrue(h.engine.downloadAllNotes().isSuccess)
        assertEquals(ids.toSet(), h.stateStore.knownCloudIds())
        return h to ids
    }

    @Test
    fun aShortSnapshotDoesNotDeleteTheNotesItFailedToDeliver() = runTest {
        val (h, ids) = syncedHarness()
        h.tick()

        // The cloud still holds all three. The read only delivers two of them.
        h.simulateTruncatedCloudRead(missingNotes = 1)
        val download = h.engine.downloadAllNotes()

        assertTrue(download.isFailure, "a snapshot short of the server's own count must not apply")
        val failure = download.exceptionOrNull()
        assertIs<IncompleteCloudSnapshotException>(failure)
        assertEquals(3, failure.expectedNoteCount)
        assertEquals(2, failure.receivedNoteCount)

        assertEquals(
            ids.toSet(),
            h.localNoteIds(),
            "no local note may be deleted on the strength of a read that lost rows",
        )
        for (id in ids) {
            assertFalse(h.stateStore.isDeleted(id), "note $id must not be tombstoned locally")
            assertFalse(id in h.cloud.tombstones, "note $id must not be tombstoned in the cloud")
        }
        assertEquals(
            ids.toSet(),
            h.stateStore.knownCloudIds(),
            "the known-id set must survive so the next good read still detects real deletions",
        )
        assertEquals(ids.toSet(), h.cloudNoteIds(), "and nothing may be removed from the cloud")
    }

    @Test
    fun theNextCompleteSnapshotReconcilesNormally() = runTest {
        val (h, ids) = syncedHarness()
        h.tick()
        h.simulateTruncatedCloudRead(missingNotes = 1)
        assertTrue(h.engine.downloadAllNotes().isFailure)

        h.tick()
        h.stopTruncatingCloudReads()
        val recovered = h.engine.downloadAllNotes()

        assertTrue(recovered.isSuccess, "refusing must be transient: got ${recovered.exceptionOrNull()}")
        assertEquals(ids.toSet(), h.localNoteIds())
        assertEquals(ids.toSet(), h.stateStore.knownCloudIds())
    }

    /**
     * The upload path reads the same snapshot to decide conflicts, and a missing id there reads as
     * "no remote copy" — which resolves in local's favour and pushes over whatever the cloud
     * actually holds, including a newer edit made on another device.
     */
    @Test
    fun aShortSnapshotDoesNotLetUploadOverwriteTheNotesItOmitted() = runTest {
        val (h, ids) = syncedHarness()
        h.tick()

        val newerOnAnotherDevice = h.clock + 5_000L
        h.remoteEdit(ids.last(), "edited elsewhere", serverUpdatedAt = newerOnAnotherDevice)
        h.tick()
        h.editLocalNote(ids.last(), "edited here")

        h.simulateTruncatedCloudRead(missingNotes = 1)
        val upload = h.engine.uploadAllNotes()

        assertTrue(upload.isFailure, "an upload must not resolve conflicts against a short read")
        assertIs<IncompleteCloudSnapshotException>(upload.exceptionOrNull())
        assertEquals(
            "edited elsewhere",
            h.cloud.notes[ids.last()]?.title,
            "the other device's newer copy must still be in the cloud",
        )
    }

    /**
     * The guard is about a *mismatch*, not about the count being zero. An account whose notes were
     * all genuinely deleted elsewhere reports zero and delivers zero, which agrees.
     */
    @Test
    fun aLegitimatelyEmptyCloudStillReconciles() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("the only note")
        h.engine.uploadAllNotes()
        h.tick()
        assertTrue(h.engine.downloadAllNotes().isSuccess)

        h.tick()
        h.remoteDelete(noteId)
        val download = h.engine.downloadAllNotes()

        assertTrue(
            download.isSuccess,
            "an agreeing count of zero is a real empty cloud: got ${download.exceptionOrNull()}",
        )
        assertFalse(noteId in h.localNoteIds())
        assertEquals(emptySet(), h.stateStore.knownCloudIds())
    }

    /**
     * A transport that cannot prove completeness reports no count, and the engine must behave for
     * it exactly as it did before this guard existed — including still refusing a wholly empty
     * answer it has no tombstones to explain.
     */
    @Test
    fun aTransportWithNoAuthoritativeCountIsUnaffected() = runTest {
        val h = SyncChaosHarness()
        val ids = listOf(h.createLocalNote("first"), h.createLocalNote("second"))
        h.engine.uploadAllNotes()
        h.tick()
        assertTrue(h.engine.downloadAllNotes().isSuccess)

        h.cloud.reportsAuthoritativeCount = false
        h.tick()
        assertTrue(
            h.engine.downloadAllNotes().isSuccess,
            "a countless transport must keep reconciling as before",
        )
        assertEquals(ids.toSet(), h.localNoteIds())

        h.tick()
        h.simulateEmptyCloudRead()
        val failedOpen = h.engine.downloadAllNotes()
        assertIs<SuspectEmptyCloudException>(
            failedOpen.exceptionOrNull(),
            "the empty-cloud guard must still be the one that catches a fail-open read",
        )
        assertEquals(ids.toSet(), h.localNoteIds())
    }
}
