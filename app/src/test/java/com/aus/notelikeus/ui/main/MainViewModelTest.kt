package com.aus.notelikeus.ui.main

import android.util.Log
import app.cash.turbine.test
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.data.remote.CloudNoteSyncCoordinator
import com.aus.notelikeus.data.remote.FirebaseAccount
import com.aus.notelikeus.data.remote.FirebaseNoteSync
import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.remote.FirestoreNotesRealtimeSync
import com.aus.notelikeus.data.remote.GoogleSignInHelper
import com.aus.notelikeus.data.remote.NoteSyncStateStore
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var repository: NoteRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var firebaseSessionManager: FirebaseSessionManager
    private lateinit var firebaseNoteSync: FirebaseNoteSync
    private lateinit var cloudNoteSyncCoordinator: CloudNoteSyncCoordinator
    private lateinit var noteSyncStateStore: NoteSyncStateStore
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        firebaseSessionManager = mockk(relaxed = true)
        firebaseNoteSync = mockk(relaxed = true)
        cloudNoteSyncCoordinator = mockk(relaxed = true)
        noteSyncStateStore = mockk(relaxed = true)
        every { repository.getActiveNotes() } returns flowOf(emptyList())
        every { repository.getArchivedNotes() } returns flowOf(emptyList())
        every { repository.getTrashedNotes() } returns flowOf(emptyList())
        every { repository.getActiveNoteCount() } returns flowOf(0)
        every { settingsRepository.isTrueDarkMode } returns flowOf(false)
        every { settingsRepository.appTheme } returns flowOf(AppTheme.AUTO)
        every { settingsRepository.recentSearches } returns flowOf(emptyList())
        every { repository.getLabels() } returns flowOf(emptyList())
        every { settingsRepository.isAppLockEnabled } returns flowOf(false)
        every { settingsRepository.noteViewMode } returns flowOf(NoteViewMode.GRID_2)
        every { settingsRepository.noteSortOrder } returns flowOf(NoteSortOrder.MANUAL)
        every { settingsRepository.useMonochromeTheme } returns flowOf(true)
        every { settingsRepository.isCloudAutoSyncEnabled } returns flowOf(true)
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = null,
            email = null,
            isGoogleAccount = false,
            isAnonymous = true
        )
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun createViewModel(): MainViewModel {
        val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
        every { sessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = null,
            email = null,
            isGoogleAccount = false,
            isAnonymous = true
        )
        return MainViewModel(
            repository,
            settingsRepository,
            mockk<NoteBackupExporter>(relaxed = true),
            mockk<NoteBackupImporter>(relaxed = true),
            firebaseSessionManager,
            firebaseNoteSync,
            mockk<GoogleSignInHelper>(relaxed = true),
            cloudNoteSyncCoordinator,
            noteSyncStateStore
        )
    }

    @Test
    fun `initial state is empty`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(emptyList<Note>(), state.notes)
            assertEquals("", state.searchQuery)
        }
    }

    @Test
    fun `onSearchQueryChange updates state`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            viewModel.onSearchQueryChange("test")
            advanceUntilIdle()
            val state = awaitItem()
            assertEquals("test", state.searchQuery)
        }
    }

    @Test
    fun `search filters notes by title after debounce`() = runTest(testDispatcher) {
        val notes = listOf(
            Note(id = 1L, title = "Work plan", content = "", timestamp = 0L, color = 0),
            Note(id = 2L, title = "Home", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.notes.size)

        viewModel.onSearchQueryChange("Work")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertEquals("Work plan", viewModel.state.value.filteredNotes.first().title)
    }

    @Test
    fun `search filters notes by checklist text`() = runTest(testDispatcher) {
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
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.notes.size)

        viewModel.onSearchQueryChange("almond")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertEquals("Groceries", viewModel.state.value.filteredNotes.first().title)
    }

    @Test
    fun `label filter limits visible notes`() = runTest(testDispatcher) {
        val workLabel = Label(id = 10L, name = "Work")
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0, labels = listOf(workLabel)),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectLabelFilter(10L)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertEquals("A", viewModel.state.value.filteredNotes.first().title)
    }

    @Test
    fun `stageEditorUndo and undoLastAction restores note`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

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
        advanceUntilIdle()

        coVerify { repository.updateNote(note.copy(isTrashed = false)) }
    }

    @Test
    fun `commitNoteOrder persists only changed positions`() = runTest(testDispatcher) {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0, position = 0),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0, position = 1)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        coEvery { repository.updateNotePositions(any()) } returns Unit
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.previewMoveNote(0, 1)
        advanceUntilIdle()
        viewModel.commitNoteOrder()
        advanceUntilIdle()

        coVerify {
            repository.updateNotePositions(match { ordered ->
                ordered.size == 2 && ordered[0].id == 2L && ordered[1].id == 1L
            })
        }
    }

    @Test
    fun `undoLastAction bumps list revision for restored swipe cards`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

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
    fun `newest sort orders pinned first then by timestamp`() = runTest(testDispatcher) {
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
    fun `oldest sort orders pinned first then by timestamp`() = runTest(testDispatcher) {
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
    fun `clearFilters resets color label and search`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

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
    fun `toggleSelectAll selects and clears visible notes`() = runTest(testDispatcher) {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleSelectAll()
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), viewModel.state.value.selectedNotes)

        viewModel.toggleSelectAll()
        advanceUntilIdle()
        assertEquals(emptySet<Long>(), viewModel.state.value.selectedNotes)
    }

    @Test
    fun `setSelectedNotesPinned updates pinned state`() = runTest(testDispatcher) {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "", timestamp = 0L, color = 0, isPinned = false),
            Note(id = 2L, title = "B", content = "", timestamp = 0L, color = 0, isPinned = true)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        coEvery { repository.updateNote(any()) } returns Unit
        viewModel = createViewModel()
        advanceUntilIdle()

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

    @Test
    fun `sign-in for different user clears prior local data before merge`() = runTest {
        every { noteSyncStateStore.lastMergedUserId() } returns "uid-a"
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid-b",
            email = "b@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coEvery { firebaseSessionManager.signInWithGoogle(any()) } returns Result.success(Unit)
        coEvery { firebaseSessionManager.verifyConnection() } returns Result.success(Unit)
        coEvery { firebaseNoteSync.downloadAllNotes() } returns Result.success(0)
        coEvery { firebaseNoteSync.uploadAllNotes() } returns Result.success(0)
        coEvery { repository.clearAllUserData() } returns Unit

        viewModel = createViewModel()
        viewModel.signInWithGoogleIdToken("token")
        advanceUntilIdle()

        coVerify { repository.clearAllUserData() }
        verify { noteSyncStateStore.clear() }
        verify { cloudNoteSyncCoordinator.clearPending() }
        verify { noteSyncStateStore.setLastMergedUserId("uid-b") }
        coVerify { firebaseNoteSync.downloadAllNotes() }
    }

    @Test
    fun `sign-in for same user does not clear local data`() = runTest {
        every { noteSyncStateStore.lastMergedUserId() } returns "uid-a"
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid-a",
            email = "a@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coEvery { firebaseSessionManager.signInWithGoogle(any()) } returns Result.success(Unit)
        coEvery { firebaseSessionManager.verifyConnection() } returns Result.success(Unit)
        coEvery { firebaseNoteSync.downloadAllNotes() } returns Result.success(0)
        coEvery { firebaseNoteSync.uploadAllNotes() } returns Result.success(0)

        viewModel = createViewModel()
        viewModel.signInWithGoogleIdToken("token")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.clearAllUserData() }
        verify(exactly = 0) { noteSyncStateStore.clear() }
        verify { noteSyncStateStore.setLastMergedUserId("uid-a") }
    }

    @Test
    fun `first sign-in with no prior merged uid does not clear local data`() = runTest {
        every { noteSyncStateStore.lastMergedUserId() } returns null
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid-new",
            email = "new@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coEvery { firebaseSessionManager.signInWithGoogle(any()) } returns Result.success(Unit)
        coEvery { firebaseSessionManager.verifyConnection() } returns Result.success(Unit)
        coEvery { firebaseNoteSync.downloadAllNotes() } returns Result.success(0)
        coEvery { firebaseNoteSync.uploadAllNotes() } returns Result.success(0)

        viewModel = createViewModel()
        viewModel.signInWithGoogleIdToken("token")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.clearAllUserData() }
        verify(exactly = 0) { noteSyncStateStore.clear() }
        verify { noteSyncStateStore.setLastMergedUserId("uid-new") }
    }

    @Test
    fun `cold start with restored Google session backfills last merged uid`() = runTest {
        every { noteSyncStateStore.lastMergedUserId() } returns null
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid-a",
            email = "a@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.clearAllUserData() }
        verify { noteSyncStateStore.setLastMergedUserId("uid-a") }
    }

    @Test
    fun `cold start with mismatched restored session clears prior local data`() = runTest {
        every { noteSyncStateStore.lastMergedUserId() } returns "uid-a"
        every { firebaseSessionManager.getCurrentAccount() } returns FirebaseAccount(
            userId = "uid-b",
            email = "b@example.com",
            isGoogleAccount = true,
            isAnonymous = false
        )
        coEvery { repository.clearAllUserData() } returns Unit

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify { repository.clearAllUserData() }
        verify { noteSyncStateStore.clear() }
        verify { cloudNoteSyncCoordinator.clearPending() }
        verify { noteSyncStateStore.setLastMergedUserId("uid-b") }
    }
}
