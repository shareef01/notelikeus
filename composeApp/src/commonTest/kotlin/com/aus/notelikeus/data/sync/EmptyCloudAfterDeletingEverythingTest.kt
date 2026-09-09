package com.aus.notelikeus.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting every cloud note is a legitimate empty cloud, and must not be confused with a failed
 * read.
 *
 * [SuspectEmptyCloudException] exists to stop a fetch that fails *open* — an expired token
 * answering with an empty list rather than an error — from being read as "everything was deleted
 * elsewhere". Its own comment says a genuine remote delete is distinguishable because it leaves
 * tombstones, "which mergeCloudTombstones has already applied above".
 *
 * The guard did not use that fact: it compared the previously-known cloud ids against an empty
 * fetch without subtracting the ids the tombstones explain. So a user who deletes their last note
 * on another device — or empties the trash on it — hit the guard on every subsequent sync, and
 * could not get out of it: `setKnownCloudIds` only runs at the end of a *successful* download, so
 * the set that trips the guard is never updated, and `uploadAllNotes` carries the same check.
 */
class EmptyCloudAfterDeletingEverythingTest {

    @Test
    fun deletingTheLastCloudNoteElsewhereDoesNotBreakSync() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("the only note")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()
        assertEquals(setOf(noteId), h.stateStore.knownCloudIds())

        h.tick()
        h.remoteDelete(noteId)

        val download = h.engine.downloadAllNotes()

        assertTrue(
            download.isSuccess,
            "an empty cloud whose every missing id has a tombstone is explained, not suspect: " +
                "got ${download.exceptionOrNull()}",
        )
        assertFalse(noteId in h.localNoteIds(), "the deleted note must be gone locally")
        assertEquals(
            emptySet(),
            h.stateStore.knownCloudIds(),
            "and the known-id set must be updated, or the next sync trips the same guard",
        )
    }

    @Test
    fun uploadingAfterDeletingEveryCloudNoteElsewhereStillWorks() = runTest {
        val h = SyncChaosHarness()
        val first = h.createLocalNote("the only note")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.tick()
        h.remoteDelete(first)

        h.tick()
        val second = h.createLocalNote("written after the wipe")
        val upload = h.engine.uploadAllNotes()

        assertTrue(
            upload.isSuccess,
            "a new note must still upload after the cloud was legitimately emptied: " +
                "got ${upload.exceptionOrNull()}",
        )
        assertTrue(second in h.cloudNoteIds())
    }

    /**
     * The guard must still fire for the case it exists for: an empty answer that no tombstone
     * explains. This is the assertion that would break if the fix were "just drop the check".
     */
    @Test
    fun anUnexplainedEmptyCloudIsStillRefused() = runTest {
        val h = SyncChaosHarness()
        val a = h.createLocalNote("one")
        val b = h.createLocalNote("two")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        // No tombstones — the notes simply did not come back. That is a failed read.
        h.cloud.notes.clear()

        val download = h.engine.downloadAllNotes()
        assertTrue(download.exceptionOrNull() is SuspectEmptyCloudException)
        assertEquals(setOf(a, b), h.localNoteIds(), "and nothing may be deleted")

        val upload = h.engine.uploadAllNotes()
        assertTrue(upload.exceptionOrNull() is SuspectEmptyCloudException)
    }

    /**
     * A partially explained empty answer is still suspect: one tombstone does not account for two
     * missing notes.
     */
    @Test
    fun anEmptyCloudWithOnlySomeDeletionsExplainedIsRefused() = runTest {
        val h = SyncChaosHarness()
        val explained = h.createLocalNote("deleted elsewhere")
        val unexplained = h.createLocalNote("still should exist")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.tick()
        h.remoteDelete(explained)
        h.cloud.notes.clear()

        val download = h.engine.downloadAllNotes()

        assertTrue(
            download.exceptionOrNull() is SuspectEmptyCloudException,
            "one tombstone cannot account for two missing notes",
        )
        assertTrue(unexplained in h.localNoteIds(), "the unexplained note must survive")
    }
}
