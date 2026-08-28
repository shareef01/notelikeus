package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteBackupRoundtripTest {

    private val repository = mockk<NoteRepository>()
    private val exporter = NoteBackupExporter(repository, "Notelikeus", "1.0.0")
    private val importer = NoteBackupImporter(repository)

    @Test
    fun `exported json can be imported again`() = runTest {
        val note = Note(
            id = 1L,
            title = "Trip",
            content = "**pack** bags",
            timestamp = 500L,
            color = -1,
            labels = listOf(Label(id = 2L, name = "Travel"))
        )
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        stubImportRepository()
        coEvery { repository.getAllLabelsSnapshot() } returns listOf(Label(id = 2L, name = "Travel"))
        coEvery { repository.getNextNotePosition() } returns 3
        coEvery { repository.insertLabel(any()) } returns 99L

        val json = exporter.createJson()
        val result = importer.importFromJson(json) as BackupImportResult.Success

        assertEquals(1, result.notesImported)
        val captured = slot<Note>()
        coVerify { repository.insertNoteWithoutSync(capture(captured)) }
        assertEquals("Trip", captured.captured.title)
        assertEquals("**pack** bags", captured.captured.content)
        assertTrue(captured.captured.labels.any { it.name == "Travel" })
    }

    @Test
    fun `import ignores legacy attachment data`() = runTest {
        val encoded = android.util.Base64.encodeToString(byteArrayOf(9, 8, 7), android.util.Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("version", 2)
            put("notes", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("title", "Image")
                    put("content", "")
                    put("timestamp", 1L)
                    put("color", 0)
                    put("attachments", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("dataBase64", encoded)
                            put("extension", "png")
                        })
                    })
                })
            })
        }.toString()

        stubImportRepository()
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0

        importer.importFromJson(json)

        val captured = slot<Note>()
        coVerify { repository.insertNoteWithoutSync(capture(captured)) }
        assertTrue(captured.captured.attachments.isEmpty())
    }

    @Test
    fun `exported json preserves pinned and checklist fields`() = runTest {
        val note = Note(
            id = 1L,
            title = "Groceries",
            content = "",
            timestamp = 500L,
            color = -1,
            isPinned = true,
            checklist = listOf(
                ChecklistItem(id = 1L, text = "Milk", isChecked = false, position = 0),
                ChecklistItem(id = 2L, text = "Eggs", isChecked = true, position = 1)
            )
        )
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        stubImportRepository()
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { repository.getNextNotePosition() } returns 0

        val json = exporter.createJson()
        val result = importer.importFromJson(json) as BackupImportResult.Success

        assertEquals(1, result.notesImported)
        val captured = slot<Note>()
        coVerify { repository.insertNoteWithoutSync(capture(captured)) }
        assertEquals(true, captured.captured.isPinned)
        assertEquals(2, captured.captured.checklist.size)
        assertEquals("Milk", captured.captured.checklist[0].text)
        assertEquals(true, captured.captured.checklist[1].isChecked)
    }

    @Test
    fun `import rejects unsupported backup version`() = runTest {
        val json = JSONObject().apply {
            put("version", NoteBackupExporter.BACKUP_VERSION + 1)
            put("notes", org.json.JSONArray())
        }.toString()

        val result = importer.importFromJson(json)
        assertTrue(result is BackupImportResult.InvalidFormat)
        assertTrue((result as BackupImportResult.InvalidFormat).message.contains("Unsupported backup version"))
    }

    private fun stubImportRepository() {
        coEvery { repository.withWriteTransaction<Unit>(any()) } coAnswers {
            val block = it.invocation.args[0] as suspend () -> Unit
            block()
        }
        coEvery { repository.insertNoteWithoutSync(any()) } returns 42L
        coEvery { repository.finalizeImportedNotes(any()) } returns Unit
    }
}
