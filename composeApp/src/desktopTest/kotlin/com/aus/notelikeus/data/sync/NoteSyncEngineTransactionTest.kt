package com.aus.notelikeus.data.sync

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.mapper.toChecklistItemEntity
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the `runInTransaction` wiring against a **real** Room database.
 *
 * [NoteSyncEngineTest] leaves `runInTransaction` at its pass-through default, so it proves
 * nothing about the production wiring in either `PlatformModule`. That wiring runs suspend DAO
 * calls *inside* a held writer connection, which has two failure modes neither a compile nor the
 * existing tests would catch: the DAO calls could fail to join the held connection and deadlock
 * waiting for it, or the transaction could fail to roll back and leave a note half-written with
 * some of its labels and checklist items missing.
 *
 * Every test is bounded by an explicit timeout so a deadlock fails the run instead of hanging it.
 */
class NoteSyncEngineTransactionTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase
    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var stateStore: FakeNoteSyncStateStore
    private lateinit var engine: NoteSyncEngine

    /**
     * Mirrors the `runInTransaction` in both `androidMain` and `desktopMain` PlatformModule.
     * If those change, this must change with them — that equivalence is the point of the test.
     */
    private fun productionTransactionRunner(
        db: NotelikeusDatabase
    ): suspend (suspend () -> Unit) -> Unit = { block ->
        db.useWriterConnection { tx -> tx.immediateTransaction { block() } }
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-tx-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(
            name = File(tempDir, "test.db").absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .build()

        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        engine = NoteSyncEngine(
            transport = transport,
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

    @Test
    fun `insert through the real transaction commits the note with its labels and checklist`() =
        runTest(timeout = TIMEOUT) {
            transport.notes[NOTE_ID] = cloudRecord(
                noteId = NOTE_ID,
                title = "From cloud",
                labels = listOf("work", "urgent"),
                checklist = listOf("first" to false, "second" to true)
            )

            val result = engine.downloadAllNotes()

            assertTrue(result.isSuccess, "download failed: ${result.exceptionOrNull()}")
            val stored = database.noteDao.getNoteById(NOTE_ID)
            assertNotNull(stored, "note was not committed")
            assertEquals("From cloud", stored.note.title)
            assertEquals(
                setOf("work", "urgent"),
                database.noteDao.getLabelsForNote(NOTE_ID).map { it.name }.toSet()
            )
            assertEquals(
                listOf("first" to false, "second" to true),
                database.noteDao.getChecklistItemsForNote(NOTE_ID)
                    .sortedBy { it.position }
                    .map { it.text to it.isChecked }
            )
        }

    @Test
    fun `update through the real transaction replaces labels and checklist rather than appending`() =
        runTest(timeout = TIMEOUT) {
            // A local note that the cloud will win over (cloud has a server timestamp, local none).
            database.noteDao.insertNote(
                Note(
                    id = NOTE_ID,
                    title = "Local",
                    content = "",
                    timestamp = 1L,
                    color = 0
                ).toNoteEntity()
            )

            transport.notes[NOTE_ID] = cloudRecord(
                noteId = NOTE_ID,
                title = "Cloud wins",
                labels = listOf("replaced"),
                checklist = listOf("only item" to false),
                serverUpdatedAt = 999_999L,
                clientTimestamp = 500L
            )

            val result = engine.downloadAllNotes()

            assertTrue(result.isSuccess, "download failed: ${result.exceptionOrNull()}")
            val stored = database.noteDao.getNoteById(NOTE_ID)
            assertNotNull(stored)
            assertEquals("Cloud wins", stored.note.title)
            assertEquals(
                listOf("replaced"),
                database.noteDao.getLabelsForNote(NOTE_ID).map { it.name }
            )
            assertEquals(
                listOf("only item"),
                database.noteDao.getChecklistItemsForNote(NOTE_ID).map { it.text }
            )
        }

    @Test
    fun `a failure part-way through the transaction rolls the whole write back`() =
        runTest(timeout = TIMEOUT) {
            val runner = productionTransactionRunner(database)

            assertFailsWith<IllegalStateException> {
                runner {
                    database.noteDao.insertNote(
                        Note(
                            id = NOTE_ID,
                            title = "Should not survive",
                            content = "",
                            timestamp = 1L,
                            color = 0
                        ).toNoteEntity()
                    )
                    database.noteDao.insertChecklistItem(
                        ChecklistItem(text = "orphan", isChecked = false, position = 0)
                            .toChecklistItemEntity(NOTE_ID)
                    )
                    // Without this the assertions below would also pass if the write had never
                    // landed at all: it pins down that the row *was* there, and that the
                    // rollback is what removed it.
                    assertNotNull(
                        database.noteDao.getNoteById(NOTE_ID),
                        "the note was never written, so this test proves nothing about rollback"
                    )
                    error("write failed half-way")
                }
            }

            assertEquals(
                emptyList(),
                database.noteDao.getAllNotesForBackup(),
                "the note survived a failed transaction — it was not rolled back"
            )
            assertEquals(emptyList(), database.noteDao.getChecklistItemsForNote(NOTE_ID))
        }

    private fun cloudRecord(
        noteId: Long,
        title: String,
        labels: List<String> = emptyList(),
        checklist: List<Pair<String, Boolean>> = emptyList(),
        serverUpdatedAt: Long? = 100_000L,
        clientTimestamp: Long? = 1_000L
    ) = CloudNoteRecord(
        noteId = noteId,
        serverUpdatedAt = serverUpdatedAt,
        clientTimestamp = clientTimestamp,
        title = title,
        content = "body",
        timestamp = clientTimestamp ?: 1_000L,
        color = 0,
        isPinned = false,
        isArchived = false,
        isTrashed = false,
        position = 0,
        reminderTimestamp = null,
        labels = labels,
        checklistItems = checklist.mapIndexed { index, (text, checked) ->
            ChecklistItemData(text = text, isChecked = checked, position = index)
        }
    )

    private companion object {
        const val UID = "uid"
        const val NOTE_ID = 42L
        val TIMEOUT = 30.seconds
    }
}
