package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.aus.notelikeus.data.attachments.positionedAttachments
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.actionToken
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorAttachmentPlacementTest {
    @Test
    fun placingEditingAndUnplacingSaveAlongsideTheExistingNote() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val storage = FakeNoteRepository()
            val body = "First 🧭\nSecond"
            val note = Note(id = 1, title = "Trip", content = body, timestamp = 1, color = 0,
                attachments = listOf(Attachment("image", 1, "file:image")))
            storage.updateNote(note)
            val repository = object : NoteRepository by storage {
                override fun getActiveNotes() = flowOf(listOf(note))
                override fun getArchivedNotes() = flowOf(emptyList<Note>())
                override fun getTrashedNotes() = flowOf(emptyList<Note>())
                override fun getLabels() = flowOf(emptyList<Label>())
            }
            val reminders = object : ReminderManager {
                override fun scheduleReminder(noteId: Long, timestamp: Long) = Unit
                override fun cancelReminder(noteId: Long) = Unit
            }
            val editor = EditorViewModel(repository, reminders, SavedStateHandle(mapOf("noteId" to 1L)),
                localCommitTokenProvider = LocalCommitTokenProvider { actionToken() })
            runCurrent()
            editor.onContentValueChange(TextFieldValue(body, TextRange("First 🧭".length)))
            assertTrue(editor.setAttachmentPlacement("image", place = true))
            assertIs<LocalSaveResult.Saved>(editor.saveLocallyAndAwait())
            assertEquals(8L, assertNotNull(storage.getNoteById(1)?.attachments?.single()?.placement).offset)

            val revised = "First 🧭 revised\nSecond"
            editor.onContentValueChange(TextFieldValue(revised))
            assertIs<LocalSaveResult.Saved>(editor.saveLocallyAndAwait())
            val edited = assertNotNull(storage.getNoteById(1))
            assertEquals(revised, edited.content)
            assertEquals(revised.indexOf('\n') + 1, positionedAttachments(edited.content, edited.attachments).single().first)

            assertTrue(editor.setAttachmentPlacement("image", place = false))
            assertIs<LocalSaveResult.Saved>(editor.saveLocallyAndAwait())
            val image = assertNotNull(storage.getNoteById(1)).attachments.single()
            assertEquals("image", image.id)
            assertNull(image.placement)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
