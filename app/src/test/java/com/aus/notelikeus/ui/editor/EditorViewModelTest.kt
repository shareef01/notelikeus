package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var viewModel: EditorViewModel
    private lateinit var repository: NoteRepository
    private lateinit var reminderScheduler: ReminderScheduler
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        every { repository.getLabels() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading existing note updates state`() = runTest {
        val note = Note(
            id = 1L,
            title = "Old Title",
            content = "Old Content",
            timestamp = 0L,
            color = 0
        )
        coEvery { repository.getNoteById(1L) } returns note
        
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to 1L))
        viewModel = EditorViewModel(repository, reminderScheduler, savedStateHandle)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("Old Title", state.title)
            assertEquals("Old Content", state.content)
            assertEquals(true, state.isNoteLoaded)
        }
    }

    @Test
    fun `onTitleChange updates state`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = EditorViewModel(repository, reminderScheduler, savedStateHandle)

        viewModel.state.test {
            awaitItem() // initial
            viewModel.onTitleChange("New Title")
            assertEquals("New Title", awaitItem().title)
        }
    }

    @Test
    fun `togglePin updates state`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = EditorViewModel(repository, reminderScheduler, savedStateHandle)

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(false, initialState.isPinned)
            
            viewModel.togglePin()
            assertEquals(true, awaitItem().isPinned)
        }
    }
}
