package com.aus.notelikeus.data.backup

import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteBackupImporterTest {

    private val repository = mockk<NoteRepository>()
    private val reminderScheduler = mockk<ReminderScheduler>(relaxed = true)
    private val importer = NoteBackupImporter(repository, reminderScheduler)

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

        val result = importer.importFromJson(json)

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

        val result = importer.importFromJson(json)

        assertEquals(1, result.notesImported)
        assertEquals(0, result.labelsCreated)
    @Test
    fun `importFromJson preserves cloudId from backup`() = runTest {
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertNoteWithResult(any()) } returns 12L

        val cloudId = "aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee"
        val json = """
            {
              "version": 3,
              "notes": [{
                "cloudId": "$cloudId",
                "title": "Imported",
                "content": "Body",
                "timestamp": 1000,
                "color": -1,
                "labels": []
              }]
            }
        """.trimIndent()

        importer.importFromJson(json)

        coVerify {
            repository.insertNoteWithResult(match { it.cloudId == cloudId && it.title == "Imported" })
        }
    }
}
