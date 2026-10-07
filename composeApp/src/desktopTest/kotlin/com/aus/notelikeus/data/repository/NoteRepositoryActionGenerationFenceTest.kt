package com.aus.notelikeus.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.data.sync.NoopSyncCoordinator
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * F-5: the production account-bound note actions, on the **real** [NoteRepositoryImpl].
 *
 * `NoteActionsController` — the controller every archive / trash / permanent-delete / restore button
 * goes through — used to call `updateNote(note)`, `deleteNote(note)` and `restoreNote(note)`, the
 * *unfenced* overloads. Those check no originating identity at all, so an action whose user intent
 * was formed in generation N committed into the dataset that replaced N whenever the two datasets
 * reuse the same note id — and note ids are per-device autoincrements, so that is the normal case
 * across a sign-out/sign-in rather than a pathological one.
 *
 * Same uid throughout: this is not an account-switch bug. A sign-out isolates (generation N → N+1,
 * `LocalAccountIsolator.isolate`), and the same account signing back in deliberately does **not**
 * isolate again (`isolateIfAccountChanged` keeps the generation for the same uid), so the
 * replacement dataset is reached with the uid unchanged and a `uid` comparison cannot see it.
 *
 * The three recorded pre-fix reds, produced by the unfenced routes the controller used to call:
 *
 * ```
 * F5-1  expected:<[replacement note]> but was:<[old-action]>   (update overwrote the row)
 * F5-2  actual value is null                                    (delete removed the row)
 * F5-3  expected:<[replacement note]> but was:<[old-action]>   (restore overwrote the row)
 * ```
 *
 * Post-fix the same scenarios drive the token-aware overloads the controller now calls. Every test
 * uses a real Room database and the real repository; only the three platform collaborators
 * (reminder, widget, sync coordinator) are test doubles.
 */
class NoteRepositoryActionGenerationFenceTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-action-fence-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(
            name = File(tempDir, "test.db").absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    // ---- F5-1 / F5-2 / F5-3 / F5-7 / F5-8: a stale action on a colliding row ----

    @Test
    fun `F5-1 a stale update cannot overwrite the replacement row`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)
            val stale = actionToken()
            seedReplacementDataset()

            val outcome = repo.updateNote(replacementLookalike(title = "old-action"), stale)

            assertIs<LocalCommitResult.StaleGeneration>(outcome)
            assertEquals(
                REPLACEMENT_TITLE,
                storedTitle(),
                "F5-8: same uid, only the generation moved — a stale update overwrote the replacement row",
            )
            assertEquals(
                REPLACEMENT_ATTACHMENTS_JSON,
                storedAttachmentsJson(),
                "a stale update rewrote the replacement row's attachment state",
            )
            assertEquals(emptyList(), coordinator.uploads, "F5-7: a stale update scheduled an upload")
        }

    @Test
    fun `F5-2 a stale delete cannot delete the replacement row`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)
            val stale = actionToken()
            seedReplacementDataset()

            val outcome = repo.deleteNote(replacementLookalike(title = "old-action"), stale)

            assertIs<LocalCommitResult.StaleGeneration>(outcome)
            assertEquals(
                REPLACEMENT_TITLE,
                storedTitle(),
                "a stale delete removed the replacement generation's note",
            )
            assertEquals(emptyList(), coordinator.deletes, "F5-7: a stale delete scheduled a delete")
        }

    @Test
    fun `F5-3 a stale restore cannot overwrite the replacement row`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)
            val stale = actionToken()
            seedReplacementDataset()

            val outcome = repo.restoreNote(replacementLookalike("old-action"), stale)

            assertIs<LocalCommitResult.StaleGeneration>(outcome)
            assertEquals(REPLACEMENT_TITLE, storedTitle(), "a stale restore overwrote the replacement row")
            assertEquals(
                REPLACEMENT_ATTACHMENTS_JSON,
                storedAttachmentsJson(),
                "a stale restore rewrote the replacement row's attachment state",
            )
            assertEquals(emptyList(), coordinator.restores, "F5-7: a stale restore scheduled a restore")
        }

    // ---- F5-4 / F5-5 / F5-6: the valid actions are unchanged ----

    @Test
    fun `F5-4 a valid update still applies in full and schedules its upload`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)
            database.noteDao.insertNote(replacementLookalike("before").toNoteEntity())

            val outcome = repo.updateNote(
                replacementLookalike("after").copy(isArchived = true),
                actionToken(),
            )

            assertIs<LocalCommitResult.Applied<Unit>>(outcome)
            assertEquals("after", storedTitle())
            assertTrue(
                assertNotNull(database.noteDao.getNoteById(NOTE_ID)).note.isArchived,
                "the valid update did not apply",
            )
            assertEquals(listOf(NOTE_ID), coordinator.uploads, "the valid update did not schedule its upload")
        }

    @Test
    fun `F5-5 a valid delete still removes the row and schedules its cloud delete`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)
            database.noteDao.insertNote(replacementLookalike("victim").toNoteEntity())

            val outcome = repo.deleteNote(replacementLookalike("victim"), actionToken())

            assertIs<LocalCommitResult.Applied<Unit>>(outcome)
            assertNull(storedTitle(), "the valid delete did not remove the row")
            assertEquals(listOf(NOTE_ID), coordinator.deletes, "the valid delete did not schedule a delete")
        }

    @Test
    fun `F5-6 a valid restore still re-inserts the row and schedules its restore`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)

            val outcome = repo.restoreNote(replacementLookalike("restored"), actionToken())

            assertIs<LocalCommitResult.Applied<Long>>(outcome)
            assertEquals("restored", storedTitle(), "the valid restore did not re-insert the row")
            assertEquals(listOf(NOTE_ID), coordinator.restores, "the valid restore did not schedule a restore")
        }

    // ---- F5-10: commit-then-isolate leaves the replacement untouched ----

    @Test
    fun `F5-10 a local commit that won under N does not leak into the replacement dataset`() =
        runTest(timeout = TIMEOUT) {
            val coordinator = RecordingSyncCoordinator()
            val repo = repository(coordinator)
            val token = actionToken()
            database.noteDao.insertNote(replacementLookalike("original").toNoteEntity())

            // The action commits first, in its own dataset.
            assertIs<LocalCommitResult.Applied<Unit>>(
                repo.updateNote(replacementLookalike("committed"), token),
            )
            assertEquals("committed", storedTitle())

            // The boundary lands afterwards. The replacement dataset is a different library, so what
            // the earlier action wrote must be gone rather than carried over — the "commit first,
            // isolation later wipes it" half of the contract.
            seedReplacementDataset()

            assertEquals(
                REPLACEMENT_TITLE,
                storedTitle(),
                "the earlier generation's committed write survived into the replacement dataset",
            )
            assertEquals(REPLACEMENT_ATTACHMENTS_JSON, storedAttachmentsJson())
        }

    // ---- fixtures ----

    /**
     * The note as a production action would hold it: the row the user was looking at *before* the
     * boundary. Its id is the colliding one, deliberately.
     */
    private fun replacementLookalike(title: String) = Note(
        id = NOTE_ID,
        title = title,
        content = "stale action body",
        timestamp = 1_000L,
        color = 0,
        serverUpdatedAt = 1_000L,
        attachments = listOf(
            Attachment(id = "att-1", noteId = NOTE_ID, storagePath = "r2:owners/$UID/att-1"),
        ),
    )

    /** The token a production action captured when the user acted — generation N. */
    private fun actionToken(): LocalCommitToken = LocalCommitGate.capture(UID)

    /**
     * The dataset that replaced N: same uid, and a row at the same id carrying deliberately
     * different state, so "the replacement survived" is a real observation rather than a guess.
     */
    private suspend fun seedReplacementDataset() {
        LocalCommitGate.isolate {
            database.noteDao.deleteAllNotes()
            database.noteDao.insertNote(
                Note(
                    id = NOTE_ID,
                    title = REPLACEMENT_TITLE,
                    content = REPLACEMENT_CONTENT,
                    timestamp = 9_000L,
                    color = 4,
                    serverUpdatedAt = 9_000L,
                    attachments = listOf(
                        Attachment(
                            id = "replacement-att",
                            noteId = NOTE_ID,
                            storagePath = "r2:replacement/att-9",
                            type = "image",
                            mimeType = "image/png",
                            sizeBytes = 0,
                        ),
                    ),
                ).toNoteEntity(),
            )
        }
    }

    private suspend fun storedTitle(): String? = database.noteDao.getNoteById(NOTE_ID)?.note?.title

    private suspend fun storedAttachmentsJson(): String? =
        database.noteDao.getNoteEntityById(NOTE_ID)?.attachmentsJson

    private fun repository(coordinator: RecordingSyncCoordinator) = NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao,
        labelDao = database.labelDao,
        reminderManager = NoopReminderManager(),
        widgetManager = NoopWidgetManager(),
        syncCoordinator = coordinator,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class NoopReminderManager : ReminderManager {
        override fun scheduleReminder(noteId: Long, timestamp: Long) {}
        override fun cancelReminder(noteId: Long) {}
    }

    private class NoopWidgetManager : PlatformWidgetManager {
        override suspend fun refreshWidgets() {}
    }

    private companion object {
        const val UID = "same-user"
        const val NOTE_ID = 42L
        const val REPLACEMENT_TITLE = "replacement note"
        const val REPLACEMENT_CONTENT = "replacement body"
        const val REPLACEMENT_ATTACHMENTS_JSON =
            """[{"id":"replacement-att","noteId":42,"storagePath":"r2:replacement/att-9","type":"image","mimeType":"image/png","sizeBytes":0}]"""

        val TIMEOUT = 60.seconds
    }
}
