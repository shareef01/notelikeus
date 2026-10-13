package com.aus.notelikeus.data.sync

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A wipe must not be reversible from a device that was offline across it — and the device must not
 * pay for that with its own notes.
 *
 * Server-side, `delete_all_user_cloud_data()` bumps a durable generation and every note or tombstone
 * write has to present it (`supabase/migrations/20261013000000_account_wipe_generation.sql`). This
 * covers the client half: a snapshot carrying a generation this device has not acknowledged stops
 * the sync *before* the delete-on-absence loop, because otherwise an emptied cloud reads as "every
 * note you knew about was deleted elsewhere" and the local copies are removed — the opposite of what
 * a wipe on another device should do to this one.
 */
class CloudWipeGenerationFenceTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase
    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var stateStore: FakeNoteSyncStateStore
    private lateinit var engine: NoteSyncEngine

    private fun productionTransactionRunner(
        db: NotelikeusDatabase
    ): suspend (suspend () -> Unit) -> Unit = { block ->
        db.useWriterConnection { tx -> tx.immediateTransaction { block() } }
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-wipe-gen-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(
            name = File(tempDir, "test.db").absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .build()

        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { UID },
            noteDao = database.noteDao,
            labelDao = database.labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success(UID) },
            platform = "desktop",
            runInTransaction = productionTransactionRunner(database)
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    /** A library this device owns locally, and which it last saw in the cloud. */
    private suspend fun seedLocalNoteInKnownCloud() {
        database.noteDao.insertNote(
            Note(id = NOTE_ID, title = "Local", content = "body", timestamp = 1L, color = 0)
                .toNoteEntity()
        )
        stateStore.setKnownCloudIds(setOf(NOTE_ID))
        stateStore.setLastMergedUserId(UID)
    }

    @Test
    fun `a wipe this device has not seen keeps the local note, uploads nothing and blocks`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNoteInKnownCloud()
            // The cloud is empty because it was deliberately wiped, and says so.
            transport.wipeGeneration = 1L

            val result = engine.downloadAllNotes()

            val failure = assertIs<CloudWipedElsewhereException>(result.exceptionOrNull())
            assertEquals(1L, failure.wipeGeneration)
            assertEquals(1, failure.localNoteCount)

            // The point of stopping: the note is still here, and nothing was sent.
            assertNotNull(database.noteDao.getNoteById(NOTE_ID), "the local note was removed")
            assertEquals(0, transport.putNotesCalls, "a wiped account had notes uploaded to it")

            assertTrue(stateStore.uploadsBlockedAfterCloudWipe(), "uploads were not blocked")
            assertEquals(1L, stateStore.wipeGeneration(), "the generation was not recorded")
        }

    @Test
    fun `an upload already queued stays refused while the wipe is unanswered`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNoteInKnownCloud()
            stateStore.setWipeGeneration(1L)
            stateStore.blockUploadsAfterCloudWipe()
            transport.wipeGeneration = 1L

            val result = engine.uploadAllNotes()

            assertIs<CloudWipedElsewhereException>(result.exceptionOrNull())
            assertEquals(0, transport.putNotesCalls, "a blocked device uploaded its notes")
            assertNotNull(database.noteDao.getNoteById(NOTE_ID))
        }

    @Test
    fun `the user's decision uploads the local notes as new ones`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNoteInKnownCloud()
            stateStore.setWipeGeneration(1L)
            stateStore.blockUploadsAfterCloudWipe()
            transport.wipeGeneration = 1L

            assertTrue(engine.allowUploadAfterCloudWipe().isSuccess)

            assertFalse(stateStore.uploadsBlockedAfterCloudWipe(), "the block was not lifted")
            // Their absence from the cloud is no longer read as a deletion, so the upload is allowed.
            assertTrue(stateStore.knownCloudIds().isEmpty(), "the known cloud ids were kept")

            val result = engine.uploadAllNotes()

            assertTrue(result.isSuccess, "upload failed: ${result.exceptionOrNull()}")
            assertEquals(1, transport.putNotesCalls, "the opted-in notes were not uploaded")
            assertTrue(transport.notes.containsKey(NOTE_ID), "the cloud did not receive the note")
        }

    @Test
    fun `a device holding nothing adopts the generation instead of failing forever`() =
        runTest(timeout = TIMEOUT) {
            // A fresh install on an account that was wiped: there is nothing local to protect, so
            // refusing would leave the account permanently broken on a new device for no gain.
            transport.wipeGeneration = 3L

            val result = engine.downloadAllNotes()

            assertTrue(result.isSuccess, "download failed: ${result.exceptionOrNull()}")
            assertEquals(3L, stateStore.wipeGeneration())
            assertFalse(stateStore.uploadsBlockedAfterCloudWipe())
        }

    @Test
    fun `an account that was never wiped syncs exactly as before`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNoteInKnownCloud()
            transport.wipeGeneration = 0L
            transport.notes[NOTE_ID] = cloudRecord(noteId = NOTE_ID, title = "From cloud")

            val result = engine.downloadAllNotes()

            assertTrue(result.isSuccess, "download failed: ${result.exceptionOrNull()}")
            assertFalse(stateStore.uploadsBlockedAfterCloudWipe())
            assertEquals(
                "From cloud",
                assertNotNull(database.noteDao.getNoteById(NOTE_ID)).note.title,
            )
        }

    @Test
    fun `the same generation is only ever news once`() =
        runTest(timeout = TIMEOUT) {
            seedLocalNoteInKnownCloud()
            transport.wipeGeneration = 1L

            assertIs<CloudWipedElsewhereException>(engine.downloadAllNotes().exceptionOrNull())

            // Answering the prompt is what should let the sync through; a second look at the same
            // generation must not raise the same stop again on its own, or the block could never be
            // lifted without the user's answer being overwritten by a restart.
            assertTrue(engine.allowUploadAfterCloudWipe().isSuccess)
            val second = engine.downloadAllNotes()

            assertTrue(second.isSuccess, "second download failed: ${second.exceptionOrNull()}")
        }

    private fun cloudRecord(noteId: Long, title: String) = CloudNoteRecord(
        noteId = noteId,
        serverUpdatedAt = 5_000L,
        clientTimestamp = 1L,
        title = title,
        content = "",
        timestamp = 1L,
        color = 0,
        isPinned = false,
        isArchived = false,
        isTrashed = false,
        position = 0,
        reminderTimestamp = null,
        labels = emptyList(),
        checklistItems = emptyList(),
        revision = 7L,
    )

    private companion object {
        const val UID = "99999999-9999-4999-8999-999999999999"
        const val NOTE_ID = 42L
        val TIMEOUT = 60.seconds
    }
}
