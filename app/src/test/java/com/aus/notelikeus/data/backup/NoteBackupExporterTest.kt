package com.aus.notelikeus.data.backup

import android.content.Context
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

class NoteBackupExporterTest {

    private val repository = mockk<NoteRepository>()
    private val context = mockk<Context>()
    private val exporter = NoteBackupExporter(repository, context)

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
        every { context.getString(any()) } returns "Notelikeus"

        val json = JSONObject(exporter.createJson())

        assertEquals(2, json.getInt("version"))
        assertEquals("1.0", json.getString("appVersion"))
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
        every { context.getString(any()) } returns "Notelikeus"

        val noteJson = JSONObject(exporter.createJson())
            .getJSONArray("notes")
            .getJSONObject(0)

        assertFalse(noteJson.has("attachments"))
    }
}
