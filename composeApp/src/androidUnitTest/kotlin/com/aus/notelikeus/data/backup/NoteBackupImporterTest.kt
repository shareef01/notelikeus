package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteBackupImporterTest {

    private val repository = mockk<NoteRepository>()
    private val importer = NoteBackupImporter(repository)

    @Test
    fun `importFromJson creates labels and notes`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertLabel(any()) } returns 1L
        coEvery { repository.insertNoteWithResult(any()) } returns 10L

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
        coVerify { repository.insertNoteWithResult(match { it.title == "Imported" && it.labels.size == 1 }) }
    }

    @Test
    fun `importFromJson reuses existing labels`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns listOf(Label(id = 5L, name = "Work"))
        coEvery { repository.getNextNotePosition() } returns 2
        coEvery { repository.insertNoteWithResult(any()) } returns 11L

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
        coVerify(exactly = 0) { repository.insertLabel(any()) }
    }

    @Test
    fun `a note that trips a limit rejects the whole backup without writing anything`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertLabel(any()) } returns 1L
        coEvery { repository.insertNoteWithResult(any()) } returns 10L

        // The first note is fine; the second exceeds MAX_NOTE_CHECKLIST. Validation runs over the
        // whole payload first, so the good note must not be committed before the bad one is seen.
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

        assert(result is BackupImportResult.InvalidFormat) { "expected InvalidFormat, got $result" }
        coVerify(exactly = 0) { repository.insertNoteWithResult(any()) }
        coVerify(exactly = 0) { repository.insertLabel(any()) }
    }

    @Test
    fun `too many root labels is rejected`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertLabel(any()) } returns 1L

        val labels = (0 until NoteBackupImporter.MAX_BACKUP_LABELS + 1)
            .joinToString(",") { """{"id":$it,"name":"l$it"}""" }
        val json = """{"version": 1, "labels": [$labels], "notes": []}"""

        val result = importer.importFromJson(json)

        assert(result is BackupImportResult.InvalidFormat) { "expected InvalidFormat, got $result" }
        coVerify(exactly = 0) { repository.insertLabel(any()) }
    }

    @Test
    fun `deeply nested json is rejected instead of exhausting the stack`() = runTest {
        // The serializer parses recursively; on stack-constrained runtimes this shape could
        // throw StackOverflowError, which the importer's Exception catch cannot contain.
        val json = "[".repeat(NoteBackupImporter.MAX_JSON_DEPTH + 8) +
            "]".repeat(NoteBackupImporter.MAX_JSON_DEPTH + 8)

        val result = importer.importFromJson(json)

        assert(result is BackupImportResult.InvalidFormat) { "expected InvalidFormat, got $result" }
        coVerify(exactly = 0) { repository.insertNoteWithResult(any()) }
    }

    @Test
    fun `an escaped quote inside a string does not end the string for the depth scanner`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertLabel(any()) } returns 1L
        coEvery { repository.insertNoteWithResult(any()) } returns 10L

        // The escaped quote must not terminate the string, so the following brackets are
        // string content and not counted towards nesting depth.
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

        assert(result is BackupImportResult.Success) { "expected Success, got $result" }
    }

    @Test
    fun `braces inside strings do not count towards nesting depth`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertLabel(any()) } returns 1L
        coEvery { repository.insertNoteWithResult(any()) } returns 10L

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

        assert(result is BackupImportResult.Success) { "expected Success, got $result" }
    }
}
