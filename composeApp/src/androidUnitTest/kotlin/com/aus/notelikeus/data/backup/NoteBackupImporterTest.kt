package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteBackupImporterTest {

    @Test
    fun `importFromJson creates labels and notes`() = runTest {
        val repository = RecordingNoteRepository()
        val importer = NoteBackupImporter(repository)

        val json = """
            {
              "version": 1,
              "labels": [{"id": 1, "name": "Work"}],
              "notes": [{
                "title": "Imported",
                "content": "Body",
                "timestamp": 1000,
                "color": -1,
                "labels": ["Work"],
                "checklist": [{"text": "Task", "isChecked": false, "position": 0}]
              }]
            }
        """.trimIndent()

        val result = importer.importFromJson(json) as BackupImportResult.Success

        assertEquals(1, result.notesImported)
        assertEquals(1, result.labelsCreated)
        assertEquals(1, repository.insertedWithoutSync.size)
        assertEquals("Imported", repository.insertedWithoutSync[0].title)
        assertEquals(1, repository.insertedWithoutSync[0].labels.size)
        assertEquals(repository.insertedWithoutSync.mapNotNull { it.id }, repository.finalizedIds)
        assertEquals(1, repository.transactionCount)
    }

    @Test
    fun `importFromJson reuses existing labels`() = runTest {
        val repository = RecordingNoteRepository(
            existingLabels = listOf(Label(id = 5L, name = "Work")),
            nextPosition = 2,
        )
        val importer = NoteBackupImporter(repository)

        val json = """
            {
              "version": 1,
              "notes": [{
                "title": "Note",
                "content": "",
                "timestamp": 1,
                "color": -1,
                "labels": ["work"]
              }]
            }
        """.trimIndent()

        val result = importer.importFromJson(json) as BackupImportResult.Success

        assertEquals(1, result.notesImported)
        assertEquals(0, result.labelsCreated)
        assertTrue(repository.insertedLabels.isEmpty())
    }

    @Test
    fun `a note that trips a limit rejects the whole backup without writing anything`() = runTest {
        val repository = RecordingNoteRepository()
        val importer = NoteBackupImporter(repository)

        val overSizedChecklist = (0 until NoteBackupImporter.MAX_NOTE_CHECKLIST + 1)
            .joinToString(",") { """{"text":"i$it","isChecked":false,"position":$it}""" }
        val json = """
            {
              "version": 1,
              "labels": [{"id": 1, "name": "Work"}],
              "notes": [
                {"title": "Good", "content": "", "timestamp": 1, "color": -1},
                {"title": "Bad", "content": "", "timestamp": 2, "color": -1,
                 "checklist": [$overSizedChecklist]}
              ]
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertTrue("expected InvalidFormat, got $result", result is BackupImportResult.InvalidFormat)
        assertTrue(repository.insertedWithoutSync.isEmpty())
        assertTrue(repository.insertedLabels.isEmpty())
        assertEquals(0, repository.transactionCount)
        assertTrue(repository.finalizedIds.isEmpty())
    }

    @Test
    fun `too many root labels is rejected`() = runTest {
        val repository = RecordingNoteRepository()
        val importer = NoteBackupImporter(repository)

        val labels = (0 until NoteBackupImporter.MAX_BACKUP_LABELS + 1)
            .joinToString(",") { """{"id":$it,"name":"l$it"}""" }
        val json = """{"version": 1, "labels": [$labels], "notes": []}"""

        val result = importer.importFromJson(json)

        assertTrue("expected InvalidFormat, got $result", result is BackupImportResult.InvalidFormat)
        assertTrue(repository.insertedLabels.isEmpty())
        assertEquals(0, repository.transactionCount)
    }

    @Test
    fun `deeply nested json is rejected instead of exhausting the stack`() = runTest {
        val repository = RecordingNoteRepository()
        val importer = NoteBackupImporter(repository)

        val json = "[".repeat(NoteBackupImporter.MAX_JSON_DEPTH + 8) +
            "]".repeat(NoteBackupImporter.MAX_JSON_DEPTH + 8)

        val result = importer.importFromJson(json)

        assertTrue("expected InvalidFormat, got $result", result is BackupImportResult.InvalidFormat)
        assertTrue(repository.insertedWithoutSync.isEmpty())
        assertEquals(0, repository.transactionCount)
    }

    @Test
    fun `an escaped quote inside a string does not end the string for the depth scanner`() = runTest {
        val repository = RecordingNoteRepository()
        val importer = NoteBackupImporter(repository)

        val json = """
            {
              "version": 1,
              "labels": [],
              "notes": [{
                "title": "Quoted",
                "content": "a\"[[",
                "timestamp": 1000,
                "color": -1,
                "labels": [],
                "checklist": []
              }]
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertTrue("expected Success, got $result", result is BackupImportResult.Success)
    }

    @Test
    fun `braces inside strings do not count towards nesting depth`() = runTest {
        val repository = RecordingNoteRepository()
        val importer = NoteBackupImporter(repository)

        val bracesInContent = "{ [ ".repeat(NoteBackupImporter.MAX_JSON_DEPTH + 20)
        val json = """
            {
              "version": 1,
              "labels": [],
              "notes": [{
                "title": "Braces",
                "content": "$bracesInContent",
                "timestamp": 1000,
                "color": -1,
                "labels": [],
                "checklist": []
              }]
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertTrue("expected Success, got $result", result is BackupImportResult.Success)
    }

    @Test
    fun `a write failure rolls back without scheduling uploads`() = runTest {
        val repository = RecordingNoteRepository(failAfterNotes = 1)
        val importer = NoteBackupImporter(repository)

        val json = """
            {
              "version": 1,
              "notes": [
                {"title": "One", "content": "", "timestamp": 1, "color": -1},
                {"title": "Two", "content": "", "timestamp": 2, "color": -1}
              ]
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertTrue("expected Error, got $result", result is BackupImportResult.Error)
        assertTrue(repository.finalizedIds.isEmpty())
    }
}

/**
 * In-memory [NoteRepository] for importer tests. [withWriteTransaction] runs the block and, on
 * failure, drops writes from that block so a mid-import throw looks like a rolled-back Room tx.
 */
private class RecordingNoteRepository(
    existingLabels: List<Label> = emptyList(),
    private val nextPosition: Int = 0,
    private val failAfterNotes: Int = Int.MAX_VALUE,
) : NoteRepository {

    val insertedWithoutSync = mutableListOf<Note>()
    val insertedLabels = mutableListOf<Label>()
    val finalizedIds = mutableListOf<Long>()
    var transactionCount = 0

    private val labels = existingLabels.toMutableList()
    private var nextNoteId = 10L
    private var nextLabelId = 1L

    override suspend fun <R> withWriteTransaction(block: suspend () -> R): R {
        transactionCount++
        val notesBefore = insertedWithoutSync.size
        val labelsBefore = insertedLabels.size
        return try {
            block()
        } catch (error: Exception) {
            while (insertedWithoutSync.size > notesBefore) insertedWithoutSync.removeLast()
            while (insertedLabels.size > labelsBefore) {
                val removed = insertedLabels.removeLast()
                labels.removeAll { it.id == removed.id }
            }
            throw error
        }
    }

    override suspend fun insertNoteWithoutSync(note: Note): Long {
        if (insertedWithoutSync.size >= failAfterNotes) {
            throw IllegalStateException("insert failed")
        }
        val id = nextNoteId++
        insertedWithoutSync += note.copy(id = id)
        return id
    }

    override suspend fun finalizeImportedNotes(ids: List<Long>) {
        finalizedIds += ids
    }

    override suspend fun getAllLabelsSnapshot(): List<Label> = labels.toList()

    override suspend fun getNextNotePosition(): Int = nextPosition

    override suspend fun insertLabel(label: Label): Long {
        val id = nextLabelId++
        val saved = Label(id = id, name = label.name)
        labels += saved
        insertedLabels += saved
        return id
    }

    override suspend fun insertNote(note: Note) { unsupported<Unit>() }
    override suspend fun insertNoteWithResult(note: Note): Long = unsupported()
    override suspend fun restoreNote(note: Note): Long = unsupported()
    override suspend fun updateNote(note: Note) { unsupported<Unit>() }
    override suspend fun updateNotePositions(notes: List<Note>) { unsupported<Unit>() }
    override suspend fun deleteNote(note: Note) { unsupported<Unit>() }
    override suspend fun clearAllUserData() { unsupported<Unit>() }
    override suspend fun getNoteById(id: Long): Note? = unsupported()
    override suspend fun getAllNotesForBackup(): List<Note> = unsupported()
    override suspend fun getCloudEligibleNoteCount(): Int = unsupported()
    override suspend fun getNotesWithActiveReminders(now: Long): List<Note> = unsupported()
    override suspend fun getNotesWithMissedReminders(now: Long): List<Note> = unsupported()
    override suspend fun clearReminderTimestamp(noteId: Long) { unsupported<Unit>() }
    override suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long) { unsupported<Unit>() }
    override suspend fun updateLabel(label: Label) { unsupported<Unit>() }
    override suspend fun deleteLabel(label: Label) { unsupported<Unit>() }
    override fun getActiveNotes(): Flow<List<Note>> = emptyFlow()
    override fun getArchivedNotes(): Flow<List<Note>> = emptyFlow()
    override fun getTrashedNotes(): Flow<List<Note>> = emptyFlow()
    override fun getActiveNoteCount(): Flow<Int> = emptyFlow()
    override fun getLabels(): Flow<List<Label>> = emptyFlow()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("not needed for importer tests")
}
