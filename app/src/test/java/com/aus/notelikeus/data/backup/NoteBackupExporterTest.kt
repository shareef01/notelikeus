package com.aus.notelikeus.data.backup

import android.content.Context
import com.aus.notelikeus.BuildConfig
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
import org.junit.Before
import org.junit.Test

class NoteBackupExporterTest {

    private val repository = mockk<NoteRepository>()
    private val context = mockk<Context>()
    private lateinit var exporter: NoteBackupExporter

    @Before
    fun setup() {
        every { context.getString(any<Int>()) } returns "Notelikeus"
        exporter = NoteBackupExporter(repository, context)
    }

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

        assertEquals(3, json.getInt("version"))
        assertEquals(BuildConfig.VERSION_NAME ?: "1.0.0", json.getString("appVersion"))
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
    fun `createJson redacts locked note content`() = runTest {
        val note = Note(
            id = 3L,
            cloudId = "22222222-2222-4222-8222-222222222222",
            title = "Secret",
            content = "Hidden body",
            timestamp = 1000L,
            color = 0,
            isLocked = true,
            labels = listOf(Label(id = 1L, name = "Private"))
        )
        coEvery { repository.getAllNotesForBackup() } returns listOf(note)
        coEvery { repository.getAllLabelsSnapshot() } returns emptyList()

        val noteJson = JSONObject(exporter.createJson())
            .getJSONArray("notes")
            .getJSONObject(0)

        assertTrue(noteJson.getBoolean("isLocked"))
        assertEquals("", noteJson.getString("title"))
        assertEquals("", noteJson.getString("content"))
        assertEquals(0, noteJson.getJSONArray("labels").length())
        assertEquals(0, noteJson.getJSONArray("checklist").length())
    }
}
