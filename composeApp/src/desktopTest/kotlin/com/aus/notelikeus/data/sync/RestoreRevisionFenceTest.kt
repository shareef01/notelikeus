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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * F-14, from the engine's side: the server fenced `restore_note` on the revision the caller
 * presents, so a restore can now be *refused* where it used to silently overwrite whatever the
 * cloud held.
 *
 * Two things have to follow from that, and both are what these tests are about:
 *  - the refusal must reach the caller as a failure, rather than being read as "nothing to record",
 *  - the restore marker must not survive it, because every later reconcile would retry a request
 *    that can only be refused again.
 */
class RestoreRevisionFenceTest {

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
        tempDir = Files.createTempDirectory("notelikeus-restore-fence-test").toFile()
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
            runInTransaction = productionTransactionRunner(database),
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a refused restore fails and leaves no marker to retry with`() = runTest(timeout = TIMEOUT) {
        database.noteDao.insertNote(
            Note(id = NOTE_ID, title = "Local", content = "body", timestamp = 1L, color = 0)
                .toNoteEntity()
        )
        stateStore.markDeleted(NOTE_ID)
        transport.restoreNoteFailure = RestoreRefusedException(NOTE_ID)

        val result = engine.restoreNote(NOTE_ID)

        assertIs<RestoreRefusedException>(result.exceptionOrNull())
        assertFalse(
            NOTE_ID in stateStore.restoredIds(),
            "the restore marker survived a refusal, so every later reconcile would retry it",
        )
        // The local undo stands: the user asked for the note to be back, and the cloud row is
        // reconciled by the next snapshot rather than by overwriting it here.
        assertFalse(stateStore.isDeleted(NOTE_ID))
        assertNotNull(database.noteDao.getNoteById(NOTE_ID))
    }

    @Test
    fun `a transient restore failure keeps the marker so it is tried again`() = runTest(timeout = TIMEOUT) {
        database.noteDao.insertNote(
            Note(id = NOTE_ID, title = "Local", content = "body", timestamp = 1L, color = 0)
                .toNoteEntity()
        )
        stateStore.markDeleted(NOTE_ID)
        transport.restoreNoteFailure = RuntimeException("offline")

        val result = engine.restoreNote(NOTE_ID)

        assertTrue(result.isFailure)
        assertTrue(
            NOTE_ID in stateStore.restoredIds(),
            "a network failure must keep the marker, or the restore is lost",
        )
    }

    private companion object {
        const val UID = "77777777-7777-4777-8777-777777777777"
        const val NOTE_ID = 42L
        val TIMEOUT = 60.seconds
    }
}
