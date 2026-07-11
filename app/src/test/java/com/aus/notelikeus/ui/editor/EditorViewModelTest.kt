package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.aus.notelikeus.data.remote.CloudNoteSyncCoordinator
import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
import androidx.compose.ui.text.input.TextFieldValue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var viewModel: EditorViewModel
    private lateinit var repository: NoteRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var cloudNoteSyncCoordinator: CloudNoteSyncCoordinator
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        cloudNoteSyncCoordinator = mockk(relaxed = true)
        every { repository.getLabels() } returns flowOf(emptyList())
        every { settingsRepository.appTheme } returns flowOf(AppTheme.AUTO)
        every { settingsRepository.isTrueDarkMode } returns flowOf(false)
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle): EditorViewModel {
        return EditorViewModel(
            repository,
            settingsRepository,
            reminderScheduler,
            cloudNoteSyncCoordinator,
            savedStateHandle
        )
    }

    private suspend fun ReceiveTurbine<EditorState>.awaitUntilLoaded(): EditorState {
        var state = awaitItem()
        while (!state.isNoteLoaded) {
            state = awaitItem()
        }
        return state
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
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            var state = awaitItem()
            while (!state.isNoteLoaded || state.title != "Old Title") {
                state = awaitItem()
            }
            assertEquals("Old Title", state.title)
            assertEquals("Old Content", state.content)
            assertEquals(true, state.isNoteLoaded)
        }
    }

    @Test
    fun `onTitleChange updates state`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isNoteLoaded)

        viewModel.onTitleChange("New Title")
        assertEquals("New Title", viewModel.state.value.title)
    }

    @Test
    fun `togglePin updates state`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isPinned)

        viewModel.togglePin()
        assertEquals(true, viewModel.state.value.isPinned)
    }

    @Test
    fun `trashNoteForDelete returns snapshot for saved note`() = runTest {
        val note = Note(
            id = 1L,
            title = "Title",
            content = "Body",
            timestamp = 0L,
            color = 0
        )
        coEvery { repository.getNoteById(1L) } returns note
        coEvery { repository.updateNote(any()) } returns Unit

        val savedStateHandle = SavedStateHandle(mapOf("noteId" to 1L))
        viewModel = createViewModel(savedStateHandle)
        advanceUntilIdle()

        val snapshot = viewModel.trashNoteForDelete()
        assertEquals("Title", snapshot?.title)
        assertEquals(true, viewModel.state.value.isTrashed)
    }

    @Test
    fun `missing note sets noteNotFound`() = runTest {
        coEvery { repository.getNoteById(99L) } returns null

        val savedStateHandle = SavedStateHandle(mapOf("noteId" to 99L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            var state = awaitItem()
            while (!state.isNoteLoaded) {
                state = awaitItem()
            }
            assertEquals(true, state.noteNotFound)
        }
    }

    @Test
    fun `checklist updates target item after reorder`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.addChecklistItem()
        viewModel.addChecklistItem()
        val firstId = viewModel.state.value.checklist.first().id!!

        viewModel.updateChecklistItem(firstId, "Buy milk", false)
        viewModel.updateChecklistItem(firstId, text = "Buy milk", isChecked = true)

        val updated = viewModel.state.value.checklist.first { it.id == firstId }
        assertEquals("Buy milk", updated.text)
        assertEquals(true, updated.isChecked)
    }

    @Test
    fun `convertContentToChecklist splits lines into items`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.onContentValueChange(TextFieldValue("Buy milk\nBuy eggs"))
        viewModel.convertContentToChecklist()

        val checklist = viewModel.state.value.checklist
        assertEquals(2, checklist.size)
        assertEquals("Buy milk", checklist[0].text)
        assertEquals("Buy eggs", checklist[1].text)
        assertEquals("", viewModel.state.value.content)
    }

    @Test
    fun `locking note cancels reminder`() = runTest {
        val note = Note(
            id = 1L,
            title = "Secret",
            content = "Body",
            timestamp = 0L,
            color = 0,
            isLocked = false,
            reminderTimestamp = System.currentTimeMillis() + 60_000
        )
        coEvery { repository.getNoteById(1L) } returns note
        coEvery { repository.updateNote(any()) } returns Unit

        val savedStateHandle = SavedStateHandle(mapOf("noteId" to 1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            var state = awaitItem()
            while (!state.isNoteLoaded) {
                state = awaitItem()
            }
            viewModel.toggleLock()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { reminderScheduler.cancelReminder(1L) }
    }

    @Test
    fun `convertChecklistToContent joins items into body`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.addChecklistItem()
        viewModel.addChecklistItem()
        val items = viewModel.state.value.checklist
        viewModel.updateChecklistItem(items[0].id!!, "Line one", false)
        viewModel.updateChecklistItem(items[1].id!!, "Line two", false)
        viewModel.convertChecklistToContent()

        assertEquals("Line one\nLine two", viewModel.state.value.content)
        assertEquals(emptyList<com.aus.notelikeus.domain.model.ChecklistItem>(), viewModel.state.value.checklist)
    }
}
