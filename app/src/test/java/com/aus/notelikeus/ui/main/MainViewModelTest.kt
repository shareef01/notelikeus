package com.aus.notelikeus.ui.main

import app.cash.turbine.test
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.data.remote.CloudNoteSyncCoordinator
import com.aus.notelikeus.data.remote.FirebaseNoteSync
import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.remote.GoogleSignInHelper
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var repository: NoteRepository
    private lateinit var settingsRepository: SettingsRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        every { repository.getActiveNotes() } returns flowOf(emptyList())
        every { settingsRepository.isTrueDarkMode } returns flowOf(false)
        every { settingsRepository.isAppLockEnabled } returns flowOf(false)
        every { settingsRepository.noteViewMode } returns flowOf(NoteViewMode.GRID_2)
        every { settingsRepository.noteSortOrder } returns flowOf(NoteSortOrder.MANUAL)
        every { settingsRepository.useMonochromeTheme } returns flowOf(true)
        every { settingsRepository.isCloudAutoSyncEnabled } returns flowOf(true)
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(
            repository,
            settingsRepository,
            mockk<NoteBackupExporter>(relaxed = true),
            mockk<NoteBackupImporter>(relaxed = true),
            mockk<FirebaseSessionManager>(relaxed = true),
            mockk<FirebaseNoteSync>(relaxed = true),
            mockk<GoogleSignInHelper>(relaxed = true),
            mockk<CloudNoteSyncCoordinator>(relaxed = true),
            mockk(relaxed = true)
        )
    }

    @Test
    fun `initial state is empty`() = runTest {
        viewModel.state.test {
            val state = awaitItem()
            assertEquals(emptyList<Note>(), state.notes)
            assertEquals("", state.searchQuery)
        }
    }

    @Test
    fun `onSearchQueryChange updates state`() = runTest {
        viewModel.state.test {
            awaitItem()
            viewModel.onSearchQueryChange("test")
            val state = awaitItem()
            assertEquals("test", state.searchQuery)
        }
    }

    @Test
    fun `search filters notes by title after debounce`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "Work plan", content = "", timestamp = 0L, color = 0),
            Note(id = 2L, title = "Home", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()

        viewModel.onSearchQueryChange("Work")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertEquals("Work plan", viewModel.state.value.filteredNotes.first().title)
    }

    @Test
    fun `search filters notes by checklist text`() = runTest {
        val notes = listOf(
            Note(
                id = 1L,
                title = "Groceries",
                content = "",
                timestamp = 0L,
                color = 0,
                checklist = listOf(ChecklistItem(text = "almond milk", isChecked = false, position = 0))
            ),
            Note(id = 2L, title = "Other", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()

        viewModel.onSearchQueryChange("almond")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertEquals("Groceries", viewModel.state.value.filteredNotes.first().title)
    }

    @Test
    fun `label filter limits visible notes`() = runTest {
        val workLabel = Label(id = 10L, name = "Work")
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0, labels = listOf(workLabel)),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()

        viewModel.selectLabelFilter(10L)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertEquals("A", viewModel.state.value.filteredNotes.first().title)
    }

    @Test
    fun `stageEditorUndo and undoLastAction restores note`() = runTest {
        val note = Note(
            id = 1L,
            title = "Title",
            content = "Body",
            timestamp = 0L,
            color = 0,
            isTrashed = true
        )
        viewModel.stageEditorUndo(note.copy(isTrashed = false), UndoAction.TRASH, "trashed")
        viewModel.undoLastAction()

        coVerify { repository.updateNote(note.copy(isTrashed = false)) }
    }

    @Test
    fun `commitNoteOrder persists only changed positions`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0, position = 0),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0, position = 1)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        coEvery { repository.updateNotePositions(any()) } returns Unit
        viewModel = createViewModel()

        viewModel.previewMoveNote(0, 1)
        advanceUntilIdle()
        viewModel.commitNoteOrder()

        coVerify { repository.updateNotePositions(match { ordered ->
            ordered.size == 2 && ordered[0].id == 2L && ordered[1].id == 1L
        }) }
    }

    @Test
    fun `undoLastAction bumps list revision for restored swipe cards`() = runTest {
        val note = Note(
            id = 5L,
            title = "Swipe",
            content = "",
            timestamp = 0L,
            color = 0,
            isArchived = true
        )
        viewModel.stageEditorUndo(note.copy(isArchived = false), UndoAction.ARCHIVE, "archived")
        val revisionBefore = viewModel.state.value.listRevision
        viewModel.undoLastAction()
        advanceUntilIdle()
        assertEquals(revisionBefore + 1, viewModel.state.value.listRevision)
    }

    @Test
    fun `newest sort orders pinned first then by timestamp`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "Old pinned", content = "", timestamp = 100L, color = 0, isPinned = true),
            Note(id = 2L, title = "New unpinned", content = "", timestamp = 300L, color = 0),
            Note(id = 3L, title = "Old unpinned", content = "", timestamp = 50L, color = 0),
            Note(id = 4L, title = "New pinned", content = "", timestamp = 200L, color = 0, isPinned = true)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        every { settingsRepository.noteSortOrder } returns flowOf(NoteSortOrder.NEWEST)
        viewModel = createViewModel()
        advanceUntilIdle()

        val titles = viewModel.state.value.filteredNotes.map { it.title }
        assertEquals(listOf("New pinned", "Old pinned", "New unpinned", "Old unpinned"), titles)
    }

    @Test
    fun `oldest sort orders pinned first then by timestamp`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "New pinned", content = "", timestamp = 300L, color = 0, isPinned = true),
            Note(id = 2L, title = "Old pinned", content = "", timestamp = 100L, color = 0, isPinned = true),
            Note(id = 3L, title = "New unpinned", content = "", timestamp = 250L, color = 0),
            Note(id = 4L, title = "Old unpinned", content = "", timestamp = 50L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        every { settingsRepository.noteSortOrder } returns flowOf(NoteSortOrder.OLDEST)
        viewModel = createViewModel()
        advanceUntilIdle()

        val titles = viewModel.state.value.filteredNotes.map { it.title }
        assertEquals(listOf("Old pinned", "New pinned", "Old unpinned", "New unpinned"), titles)
    }

    @Test
    fun `clearFilters resets color label and search`() = runTest {
        viewModel.onSearchQueryChange("query")
        viewModel.selectColorFilter(1)
        viewModel.selectLabelFilter(2L)
        advanceUntilIdle()

        viewModel.clearFilters()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(null, state.selectedColor)
        assertEquals(null, state.selectedLabelId)
        assertEquals("", state.searchQuery)
    }

    @Test
    fun `toggleSelectAll selects and clears visible notes`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleSelectAll()
        assertEquals(setOf(1L, 2L), viewModel.state.value.selectedNotes)

        viewModel.toggleSelectAll()
        assertEquals(emptySet<Long>(), viewModel.state.value.selectedNotes)
    }

    @Test
    fun `setSelectedNotesPinned updates pinned state`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0, isPinned = false),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0, isPinned = true)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        coEvery { repository.updateNote(any()) } returns Unit
        viewModel = createViewModel()

        viewModel.toggleNoteSelection(1L)
        viewModel.toggleNoteSelection(2L)
        viewModel.setSelectedNotesPinned(pin = true)
        advanceUntilIdle()

        coVerify {
            repository.updateNote(match { it.id == 1L && it.isPinned })
            repository.updateNote(match { it.id == 2L && it.isPinned })
        }
        assertEquals(emptySet<Long>(), viewModel.state.value.selectedNotes)
    }
}
