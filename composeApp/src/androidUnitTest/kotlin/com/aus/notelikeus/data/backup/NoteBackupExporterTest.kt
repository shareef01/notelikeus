package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteBackupExporterTest {

    private val repository = mockk<NoteRepository>()
    private val exporter = NoteBackupExporter(repository, "Notelikeus", "1.0.0")

    @Test
    fun `createJson includes notes and labels`() = runTest {
        val note = Note(
            id = 1L,
            title = "Test",
            content = "Body",
            timestamp = 1000L,
            color = 0xFFFFFFFF.toInt(),
            labels = listOf(Label(id = 2L, name = "Work"))
        )
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns listOf(Label(id = 2L, name = "Work"))

        val json = JSONObject(exporter.createJson())

        assertEquals(NoteBackupExporter.BACKUP_VERSION, json.getInt("version"))
        assertEquals("1.0.0", json.getString("appVersion"))
        assertEquals(1, json.getJSONArray("notes").length())
        assertEquals(1, json.getJSONArray("labels").length())
        assertEquals("Test", json.getJSONArray("notes").getJSONObject(0).getString("title"))
        assertTrue(json.getJSONArray("notes").getJSONObject(0).getJSONArray("labels").getString(0) == "Work")
    }

    @Test
    fun `createJson omits attachments`() = runTest {
        val note = Note(
            id = 1L,
            title = "Photo note",
            content = "",
            timestamp = 1000L,
            color = 0
        )
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val noteJson = JSONObject(exporter.createJson())
            .getJSONArray("notes")
            .getJSONObject(0)

        assertFalse(noteJson.has("attachments"))
    }

    @Test
    fun `createJson rejects note with title exceeding MAX_FIELD_CHARS`() = runTest {
        val longTitle = "a".repeat(NoteBackupImporter.MAX_FIELD_CHARS + 1)
        val note = Note(id = 1L, title = longTitle, content = "body", timestamp = 1L, color = 0)
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { exporter.createJson() }
        }
        assertTrue(ex.message!!.contains("exceeds maximum supported length"))
    }

    @Test
    fun `createJson rejects note with content exceeding MAX_CONTENT_CHARS`() = runTest {
        val longContent = "a".repeat(NoteBackupImporter.MAX_CONTENT_CHARS + 1)
        val note = Note(id = 1L, title = "Title", content = longContent, timestamp = 1L, color = 0)
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { exporter.createJson() }
        }
        assertTrue(ex.message!!.contains("exceeds maximum supported length"))
    }

    @Test
    fun `createJson rejects note with checklist items exceeding MAX_NOTE_CHECKLIST`() = runTest {
        val items = (0..NoteBackupImporter.MAX_NOTE_CHECKLIST).map {
            com.aus.notelikeus.domain.model.ChecklistItem(id = it.toLong(), text = "item $it", isChecked = false, position = it)
        }
        val note = Note(id = 1L, title = "Title", content = "", timestamp = 1L, color = 0, checklist = items)
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { exporter.createJson() }
        }
        assertTrue(ex.message!!.contains("has too many checklist items"))
    }

    @Test
    fun `createJson rejects library whose serialized bytes exceed MAX_BACKUP_CHARS (Matrix D)`() = runTest {
        // 300 notes with 90k characters content each = ~27 MB serialized UTF-8 bytes > 25 MiB
        val largeContent = "x".repeat(90_000)
        val notes = (1..300).map {
            Note(id = it.toLong(), title = "Note $it", content = largeContent, timestamp = it.toLong(), color = 0)
        }
        coEvery { repository.getAllNotesForBackup() } returns notes
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { exporter.createJson() }
        }
        assertTrue(ex.message!!.contains("exceeds maximum supported limit"))
    }

    @Test
    fun `createJson accounts for multibyte UTF-8 characters and emojis (Matrix E)`() = runTest {
        // 4-byte emoji: each character is 4 bytes in UTF-8
        val emojiString = "🚀".repeat(10)
        val note = Note(id = 1L, title = emojiString, content = "Unicode: こんにちは", timestamp = 1L, color = 0)
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val json = exporter.createJson()
        assertTrue(json.contains("🚀"))
        assertTrue(json.contains("こんにちは"))
    }

    @Test
    fun `Phase A1 Parameterized Note-Count Boundary (5000, 5001, 24999, 25000 accepted, 25001 rejected)`() {
        val boundaryCounts = listOf(
            5_000 to true,
            5_001 to true,
            24_999 to true,
            25_000 to true,
            25_001 to false
        )
        for ((count, shouldAccept) in boundaryCounts) {
            val notes = (1..count).map {
                Note(id = it.toLong(), title = "n", content = "", timestamp = 1L, color = 0)
            }
            coEvery { repository.getAllNotesForBackup() } returns notes
            coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

            if (shouldAccept) {
                val json = kotlinx.coroutines.runBlocking { exporter.createJson() }
                assertTrue(json.isNotEmpty())
            } else {
                val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking { exporter.createJson() }
                }
                assertTrue(ex.message!!.contains("maximum supported is 25000"))
            }
        }
    }
}
