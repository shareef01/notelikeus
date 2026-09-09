package com.aus.notelikeus.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The invariants the sync engine must hold no matter where a failure lands.
 *
 * Each test states an invariant in its name, drives [SyncChaosHarness] through an interleaving
 * that could break it, and asserts only user-visible state. None of them assert call order or
 * which internal branch ran — an engine that reaches the same end state by a different route is
 * not a regression, and a test that says otherwise makes the engine harder to fix, not safer.
 *
 * Where an assertion needs one, the comment says which failure mode it is standing guard over.
 */
class SyncInvariantTest {

    /**
     * Invariant: a locally acknowledged save survives process death, whatever the network did.
     *
     * This is the whole local-first promise. The upload is allowed to fail; the note is not.
     */
    @Test
    fun aLocalSaveSurvivesAProcessDeathDuringUpload() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("groceries")

        h.transport.failOnce("putNotes")
        assertTrue(h.engine.uploadAllNotes().isFailure, "the upload was made to fail")

        h.restartProcess()

        assertEquals(setOf(noteId), h.localNoteIds(), "the note must still be on the device")
        assertEquals("groceries", h.localNote(noteId)?.title)
        assertFalse(h.stateStore.isDeleted(noteId), "a failed upload is not a deletion")
    }

    /**
     * Invariant: an unsynced local note is still pending after a restart, not silently dropped.
     *
     * The retry has to be able to find it. A note that survives the restart but is no longer
     * eligible for upload is data loss with a delay on it.
     */
    @Test
    fun anUnsyncedNoteIsStillUploadedAfterARestart() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("groceries")

        h.transport.failOnce("putNotes")
        h.engine.uploadAllNotes()
        h.restartProcess()

        assertTrue(h.engine.uploadAllNotes().isSuccess)
        assertEquals(setOf(noteId), h.cloudNoteIds(), "the retry must push the note")
    }

    /**
     * Invariant: a failed cloud read never deletes local data.
     *
     * The failure mode this guards is the worst one in the system: a transport that fails *open*
     * — an expired token answering with an empty list rather than an error — reads as "everything
     * was deleted elsewhere", and a download would then remove the user's whole library.
     */
    @Test
    fun anEmptyCloudReadCannotDeleteLocalNotes() = runTest {
        val h = SyncChaosHarness()
        val a = h.createLocalNote("one")
        val b = h.createLocalNote("two")
        h.engine.uploadAllNotes()
        h.tick()
        assertTrue(h.engine.downloadAllNotes().isSuccess)
        assertEquals(setOf(a, b), h.stateStore.knownCloudIds())

        h.simulateEmptyCloudRead()
        val result = h.engine.downloadAllNotes()

        assertTrue(result.isFailure, "an unexplained empty cloud must refuse the sync")
        assertTrue(
            result.exceptionOrNull() is SuspectEmptyCloudException,
            "and must say why, so the UI can offer a retry rather than report success",
        )
        assertEquals(setOf(a, b), h.localNoteIds(), "both notes must still be on the device")
    }

    /**
     * Invariant: an empty cloud cannot make an upload overwrite what is really there either.
     *
     * The same failed-open read pointing the other way. `uploadAllNotes` reads an absent remote
     * note as "nothing newer exists" and pushes the local copy over it.
     */
    @Test
    fun anEmptyCloudReadCannotOverwriteRemoteNotesOnUpload() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("local version")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.simulateEmptyCloudRead()
        h.editLocalNote(noteId, "stale local edit")

        val result = h.engine.uploadAllNotes()

        assertTrue(result.isFailure, "an unexplained empty cloud must refuse the upload")
        assertTrue(result.exceptionOrNull() is SuspectEmptyCloudException)
    }

    /**
     * Invariant: a server-confirmed tombstone cannot be undone by a stale local copy.
     *
     * A note deleted on another device must stay deleted here, even though this device still has
     * the row and would otherwise treat it as a note the cloud is missing and push it back.
     */
    @Test
    fun aRemoteDeleteIsNotResurrectedByTheLocalCopy() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("shared")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.tick()
        h.remoteDelete(noteId)
        assertTrue(h.engine.downloadAllNotes().isSuccess)

        assertFalse(noteId in h.localNoteIds(), "the local row must be purged")
        assertTrue(h.stateStore.isDeleted(noteId), "and the tombstone kept")

        // The real test: another sync must not bring it back.
        h.tick()
        h.engine.downloadAllNotes()
        h.engine.uploadAllNotes()
        assertFalse(noteId in h.localNoteIds())
        assertFalse(noteId in h.cloudNoteIds(), "an upload must not re-create a tombstoned note")
    }

    /**
     * Invariant: a delete survives process death between the local tombstone and the cloud one.
     *
     * The local tombstone is written first precisely so this window cannot resurrect the note.
     * If the ordering were reversed, a crash here would leave a live local note and a cloud
     * delete — and the next sync would push the note straight back up.
     */
    @Test
    fun aDeleteInterruptedBeforeItsCloudTombstoneStaysDeleted() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("secret")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.tick()
        h.transport.failOnce("writeTombstone")
        assertTrue(h.engine.deleteNote(noteId).isFailure, "the cloud half was made to fail")

        h.restartProcess()

        assertTrue(h.stateStore.isDeleted(noteId), "the local tombstone must have landed first")

        h.tick()
        assertTrue(h.engine.downloadAllNotes().isSuccess)
        assertFalse(noteId in h.localNoteIds(), "the note must not come back locally")
        assertFalse(noteId in h.cloudNoteIds(), "and the next sync must finish the cloud delete")
    }

    /**
     * Invariant: a restore beats a stale tombstone across a restart.
     *
     * The restore marker is written before anything else so that a crash mid-restore cannot let
     * the still-present cloud tombstone re-purge a note the user has just brought back.
     */
    @Test
    fun aRestoreInterruptedMidFlightIsNotUndoneByTheStaleTombstone() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("recovered")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.tick()
        h.engine.deleteNote(noteId)
        assertTrue(h.stateStore.isDeleted(noteId))

        // The user restores it: the row is back locally, but the cloud call fails.
        h.tick()
        h.createLocalNote("recovered", id = noteId)
        h.transport.failOnce("restoreNote")
        assertTrue(h.engine.restoreNote(noteId).isFailure)

        h.restartProcess()

        assertTrue(noteId in h.stateStore.restoredIds(), "the restore marker must survive")
        assertFalse(h.stateStore.isDeleted(noteId), "and the local tombstone must be gone")

        h.tick()
        assertTrue(h.engine.downloadAllNotes().isSuccess)
        assertTrue(noteId in h.localNoteIds(), "the restored note must still be here")
        assertTrue(noteId in h.cloudNoteIds(), "and the sync must finish the cloud restore")
    }

    /**
     * Invariant: a delete after a restore wins.
     *
     * The two markers are mutually exclusive by design — a leftover restore marker would otherwise
     * make the next merge ignore the tombstone the user just created.
     */
    @Test
    fun aDeleteAfterARestoreIsNotUndoneByTheRestoreMarker() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("changed my mind")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        h.tick()
        h.engine.deleteNote(noteId)
        h.createLocalNote("changed my mind", id = noteId)
        h.transport.failOnce("restoreNote")
        h.engine.restoreNote(noteId)
        assertTrue(noteId in h.stateStore.restoredIds())

        h.tick()
        assertTrue(h.engine.deleteNote(noteId).isSuccess)

        assertFalse(noteId in h.stateStore.restoredIds(), "the delete must cancel the restore")
        assertTrue(h.stateStore.isDeleted(noteId))

        h.tick()
        h.engine.downloadAllNotes()
        assertFalse(noteId in h.localNoteIds(), "the later delete must be the one that stands")
    }

    /**
     * Invariant: a confirmed server revision beats a local clock, in both directions.
     *
     * The client clock is spoofable and skew-prone. A device whose clock is a year fast must not
     * be able to overwrite a revision the server has already stamped.
     */
    @Test
    fun aNewerServerRevisionWinsAgainstASkewedClientClock() = runTest {
        val h = SyncChaosHarness()
        val noteId = h.createLocalNote("original")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        // Another device commits a newer revision; this device's clock then runs a year ahead.
        h.tick()
        h.remoteEdit(noteId, "remote wins", serverUpdatedAt = 999_999_999L)
        h.clock += 365L * 24 * 60 * 60 * 1000
        h.editLocalNote(noteId, "skewed local")

        assertTrue(h.engine.downloadAllNotes().isSuccess)

        assertEquals(
            "remote wins",
            h.localNote(noteId)?.title,
            "a confirmed newer server revision must win over a fast client clock",
        )
    }

    /**
     * Invariant: signing into a different account never mixes the two libraries.
     *
     * Note ids here are small local autoincrements, so account B's note 1 and account A's note 1
     * collide. Leftover state from A must not reach B's cloud at all.
     */
    @Test
    fun anAccountSwitchCannotCarryTheFirstAccountsNotesOrTombstones() = runTest {
        val h = SyncChaosHarness(initialUid = "user-a")
        val aNote = h.createLocalNote("account A note")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()
        h.tick()
        h.engine.deleteNote(aNote)
        assertTrue(h.stateStore.isDeleted(aNote))

        h.signInAs("user-b")

        assertEquals(emptySet(), h.localNoteIds(), "account A's notes must be gone from the device")
        assertEquals(emptySet(), h.stateStore.deletedIds(), "and so must its tombstones")
        assertEquals(emptySet(), h.stateStore.knownCloudIds())

        // B's own note must sync normally, and must not be deleted by A's leftover tombstone.
        val bNote = h.createLocalNote("account B note", id = aNote)
        h.tick()
        assertTrue(h.engine.uploadAllNotes().isSuccess)
        assertEquals(setOf(bNote), h.cloudNoteIds())
        assertEquals("account B note", h.cloud.notes[bNote]?.title)
    }

    /**
     * Invariant: an account switch that leaves state behind is refused rather than guessed at.
     *
     * The isolator is what normally clears this. If it did not run — a crash between sign-out and
     * sign-in — the engine must refuse rather than apply one account's deletes to another's ids.
     */
    @Test
    fun syncingAsADifferentAccountWithLeftoverStateIsRefused() = runTest {
        val h = SyncChaosHarness(initialUid = "user-a")
        h.createLocalNote("account A note")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()
        assertEquals("user-a", h.stateStore.lastMergedUserId())

        // Sign in as B *without* isolating — the crash window the isolator normally closes.
        h.signInAs("user-a")
        h.uidOverrideForTest("user-b")

        val download = h.engine.downloadAllNotes()
        val upload = h.engine.uploadAllNotes()

        assertTrue(download.exceptionOrNull() is WrongAccountSyncException)
        assertTrue(upload.exceptionOrNull() is WrongAccountSyncException)
    }

    /**
     * Invariant: guest notes made before signing in are uploaded, not discarded.
     *
     * The mirror image of the isolation rule above — a first-ever sign-in has no previous account
     * to protect against, and dropping the notes the user wrote as a guest would be data loss.
     */
    @Test
    fun notesWrittenAsAGuestUploadOnAFirstSignIn() = runTest {
        val h = SyncChaosHarness(initialUid = "user-a")
        val guestNote = h.createLocalNote("written before signing in")
        assertEquals(null, h.stateStore.lastMergedUserId(), "no account has merged on this device")

        assertTrue(h.engine.uploadAllNotes().isSuccess)

        assertEquals(setOf(guestNote), h.cloudNoteIds())
        assertEquals(setOf(guestNote), h.localNoteIds())
    }

    /**
     * Invariant: repeating a sync converges. Running it twice must not change anything the second
     * time, or produce duplicates.
     */
    @Test
    fun repeatingASyncIsIdempotent() = runTest {
        val h = SyncChaosHarness()
        val a = h.createLocalNote("one")
        val b = h.createLocalNote("two")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        val localAfterFirst = h.localNoteIds()
        val cloudAfterFirst = h.cloudNoteIds()
        val titlesAfterFirst = localAfterFirst.associateWith { h.localNote(it)?.title }

        repeat(3) {
            h.tick()
            assertTrue(h.engine.downloadAllNotes().isSuccess)
            assertTrue(h.engine.uploadAllNotes().isSuccess)
        }

        assertEquals(setOf(a, b), localAfterFirst)
        assertEquals(localAfterFirst, h.localNoteIds(), "repeated syncs must not add or drop notes")
        assertEquals(cloudAfterFirst, h.cloudNoteIds())
        assertEquals(titlesAfterFirst, h.localNoteIds().associateWith { h.localNote(it)?.title })
    }

    /**
     * Invariant: recovery converges after an arbitrary run of injected failures.
     *
     * Deterministic rather than random: the sequence is fixed so a failure here is reproducible
     * by re-running the test, which is the only kind of concurrency test worth having.
     */
    @Test
    fun theDeviceConvergesAfterAStormOfFailedSyncs() = runTest {
        val h = SyncChaosHarness()
        val kept = h.createLocalNote("kept")
        val deleted = h.createLocalNote("deleted")
        h.engine.uploadAllNotes()
        h.tick()
        h.engine.downloadAllNotes()

        val faults = listOf(
            "putNotes", "fetchNotes", "writeTombstone", "fetchTombstones",
            "deleteNotes", "putNotes", "fetchNotes",
        )
        for (operation in faults) {
            h.tick()
            h.transport.failOnce(operation)
            runCatching { h.engine.downloadAllNotes() }
            h.transport.failOnce(operation)
            runCatching { h.engine.uploadAllNotes() }
        }

        h.tick()
        h.editLocalNote(kept, "kept and edited")
        h.tick()
        h.transport.failOnce("deleteNotes")
        runCatching { h.engine.deleteNote(deleted) }
        h.restartProcess()

        // Everything now succeeds. Two clean rounds must reach a stable, correct state.
        repeat(2) {
            h.tick()
            assertTrue(h.engine.downloadAllNotes().isSuccess, "recovery download must succeed")
            assertTrue(h.engine.uploadAllNotes().isSuccess, "recovery upload must succeed")
        }

        assertEquals(setOf(kept), h.localNoteIds(), "the surviving note, and only it")
        assertEquals("kept and edited", h.localNote(kept)?.title, "the edit must not be lost")
        assertEquals(setOf(kept), h.cloudNoteIds(), "the cloud must agree")
        assertTrue(h.stateStore.isDeleted(deleted), "the deleted note must stay deleted")
    }
}
