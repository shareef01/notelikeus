package com.aus.notelikeus.ui.main

import android.util.Log
import app.cash.turbine.test
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.domain.repository.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var repository: NoteRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var syncManager: SyncManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        
        every { repository.getActiveNotes() } returns flowOf(emptyList())
        every { settingsRepository.isAppLockEnabled } returns flowOf(false)
        every { settingsRepository.noteViewMode } returns flowOf(NoteViewMode.GRID_2)
        every { settingsRepository.noteSortOrder } returns flowOf(NoteSortOrder.MANUAL)
        every { settingsRepository.isCloudAutoSyncEnabled } returns flowOf(true)
        every { syncManager.cloudAccount } returns MutableStateFlow(CloudAccount())
        every { syncManager.syncStatus } returns MutableStateFlow(CloudSyncStatus.Synced)
        every { syncManager.pendingEvent } returns MutableStateFlow<CloudSyncEvent?>(null)

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    /**
     * @param backgroundDispatcher the one the query pass runs on. Unconfined by default, so tests
     *   see finished state without advancing; pass a standard dispatcher to hold the pass open and
     *   observe what the UI would see while it is still running.
     */
    private fun createViewModel(
        backgroundDispatcher: CoroutineDispatcher = testDispatcher
    ): MainViewModel {
        return MainViewModel(
            repository,
            settingsRepository,
            mockk<NoteBackupExporter>(relaxed = true),
            mockk<NoteBackupImporter>(relaxed = true),
            syncManager,
            backgroundDispatcher
        )
    }

    /**
     * "Loading" has to mean "I do not yet know what to show", or the empty state gets shown over a
     * library that is merely still being filtered.
     *
     * The flag used to clear the moment the DAO emitted, but the query runs off the main thread, so
     * there was a window publishing isLoading = false with filteredNotes still empty. The list read
     * that as an empty library and rendered "Notes you add appear here" on top of four notes, until
     * the pass came back. Caught on the emulator, where the window was long enough to screenshot.
     */
    @Test
    fun `loading does not end until the first query has actually run`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "Recipes", content = "", timestamp = 0L, color = 0),
            Note(id = 2L, title = "Garden", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        // A standard dispatcher holds the query pass open, which is the only way to observe the
        // state the UI actually rendered. The shared unconfined one finishes it before construction
        // returns, so the window this test exists for would not exist.
        viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        // The DAO has emitted, but nothing has filtered it yet. Claiming to be done here is what
        // put the empty state on screen.
        assertTrue(viewModel.state.value.filteredNotes.isEmpty())
        assertTrue("loading ended before anything was filtered", viewModel.state.value.isLoading)

        testScheduler.advanceUntilIdle()

        assertEquals(2, viewModel.state.value.filteredNotes.size)
        assertFalse(viewModel.state.value.isLoading)
    }

    /**
     * The root cause, and the harder half.
     *
     * Restoring the stored sort and view at startup pushes them through the same query funnel as a
     * user tapping them, so passes run before the DAO has emitted anything. Those finish instantly
     * against an empty list, and a rule of "loading ends when a query finishes" therefore ended it
     * before there was anything to show — which is how the empty state came to sit on top of four
     * notes for as long as opening an encrypted database takes.
     */
    @Test
    fun `a query that runs before the notes arrive does not end loading`() = runTest {
        val notes = MutableSharedFlow<List<Note>>(replay = 0)
        every { repository.getActiveNotes() } returns notes
        viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        // Settings have landed and run their passes; the DAO has emitted nothing.
        viewModel.setSortOrder(NoteSortOrder.NEWEST)
        viewModel.setViewMode(NoteViewMode.LIST)
        testScheduler.advanceUntilIdle()

        assertTrue("loading ended before any notes arrived", viewModel.state.value.isLoading)

        notes.emit(listOf(Note(id = 1L, title = "Recipes", content = "", timestamp = 0L, color = 0)))
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(1, viewModel.state.value.filteredNotes.size)
    }

    /** A genuinely empty library still has to stop loading, or the spinner never goes away. */
    @Test
    fun `an empty library finishes loading`() = runTest {
        every { repository.getActiveNotes() } returns flowOf(emptyList())
        viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.filteredNotes.isEmpty())
        assertFalse("the spinner would never clear", viewModel.state.value.isLoading)
    }

    /**
     * cloudSyncedNoteCount was declared in MainState, threaded down to ProfileSheet and rendered as
     * "Last sync: %d notes" -- and never assigned by anything. That row therefore read
     * "Last sync: 0 notes" permanently, on every device, however much had actually synced.
     */
    @Test
    fun `a completed sync reports how many notes it moved`() = runTest {
        val events = MutableStateFlow<CloudSyncEvent?>(null)
        every { syncManager.pendingEvent } returns events
        viewModel = createViewModel()

        events.value = CloudSyncEvent.Uploaded(7)
        testScheduler.advanceUntilIdle()
        assertEquals(7, viewModel.state.value.cloudSyncedNoteCount)

        events.value = CloudSyncEvent.Downloaded(3)
        testScheduler.advanceUntilIdle()
        assertEquals(3, viewModel.state.value.cloudSyncedNoteCount)
    }

    @Test
    fun `an event without a count leaves the last one alone`() = runTest {
        val events = MutableStateFlow<CloudSyncEvent?>(null)
        every { syncManager.pendingEvent } returns events
        viewModel = createViewModel()

        events.value = CloudSyncEvent.Uploaded(5)
        testScheduler.advanceUntilIdle()
        events.value = CloudSyncEvent.Failure("network")
        testScheduler.advanceUntilIdle()

        // A failure says nothing about how many notes are synced, so the figure must not reset.
        assertEquals(5, viewModel.state.value.cloudSyncedNoteCount)
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

        coVerify { repository.updateNote(match { it.id == 1L && !it.isTrashed }) }
    }

    @Test
    fun `settings are not reported loaded until the app lock flow actually emits`() = runTest {
        val appLock = MutableSharedFlow<Boolean>(replay = 0)
        every { settingsRepository.isAppLockEnabled } returns appLock
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.areSettingsLoaded)
        assertEquals(false, viewModel.state.value.isAppLockEnabled)

        appLock.emit(true)
        advanceUntilIdle()

        assertEquals(true, viewModel.state.value.areSettingsLoaded)
        assertEquals(true, viewModel.state.value.isAppLockEnabled)
    }

    @Test
    fun `an unreadable app lock setting still reports loaded and fails closed`() = runTest {
        every { settingsRepository.isAppLockEnabled } returns
            flow { throw java.io.IOException("corrupt preferences") }
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(true, viewModel.state.value.areSettingsLoaded)
        assertEquals(true, viewModel.state.value.isAppLockEnabled)
    }

    @Test
    fun `commitNoteOrder persists changed positions`() = runTest {
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
        advanceUntilIdle()

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

    @Test
    fun `signInWithGoogleIdToken calls syncManager`() = runTest {
        coEvery { syncManager.signInWithGoogle(any()) } returns Result.success(Unit)
        
        viewModel.signInWithGoogleIdToken("token")
        advanceUntilIdle()
        
        coVerify { syncManager.signInWithGoogle("token") }
    }

    @Test
    fun `signOutFromCloud calls syncManager`() = runTest {
        coEvery { syncManager.signOut(any()) } returns Result.success(Unit)

        viewModel.signOutFromCloud(true)
        advanceUntilIdle()

        coVerify { syncManager.signOut(true) }
    }

    @Test
    fun `a failed token exchange surfaces instead of silently returning to the gate`() = runTest {
        coEvery { syncManager.signInWithGoogle(any()) } returns
            Result.failure(IllegalStateException("bad token"))

        viewModel.signInWithGoogleIdToken("token")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(false, state.isSigningIn)
        assertEquals(
            CloudSyncEvent.Failure("bad token"),
            state.pendingCloudSyncEvent
        )
    }

    @Test
    fun `reportGoogleSignInFailure surfaces platform sign-in errors and stops the spinner`() = runTest {
        viewModel.signInWithGoogleIdToken("token")
        viewModel.reportGoogleSignInFailure(IllegalStateException("user cancelled"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(false, state.isSigningIn)
        assertEquals(
            CloudSyncEvent.Failure("user cancelled"),
            state.pendingCloudSyncEvent
        )
    }
    // ---- search operators ----

    /**
     * `label:` resolves against the user's actual labels, which is the half the parser cannot do.
     */
    @Test
    fun `a label operator becomes a real label filter`() = runTest {
        val work = Label(id = 42L, name = "Work")
        every { repository.getLabels() } returns flowOf(listOf(work))
        viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("label:work milk")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(setOf(42L), state.query.labels)
        // The free text is what remains after the operator is lifted out...
        assertEquals("milk", state.query.text)
        // ...but the box still shows everything the user typed.
        assertEquals("label:work milk", state.searchQuery)
    }

    @Test
    fun `an unknown label matches nothing rather than everything`() = runTest {
        every { repository.getLabels() } returns flowOf(emptyList())
        viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("label:nosuchlabel")
        testScheduler.advanceUntilIdle()

        // No id to filter on, so the operator contributes nothing -- and critically it does not
        // fall through to free text, which would search the notes for "label:nosuchlabel".
        assertEquals(emptySet<Long>(), viewModel.state.value.query.labels)
        assertEquals("", viewModel.state.value.query.text)
    }

    @Test
    fun `a colour operator expands to both palette variants`() = runTest {
        viewModel.onSearchQueryChange("color:green")
        testScheduler.advanceUntilIdle()

        val colors = viewModel.state.value.query.colors
        // Both the light and the dark green, so a note coloured under either theme still matches.
        assertEquals(2, colors.size)
    }

    @Test
    fun `deleting an operator removes exactly that filter`() = runTest {
        val work = Label(id = 42L, name = "Work")
        every { repository.getLabels() } returns flowOf(listOf(work))
        viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("label:work")
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(42L), viewModel.state.value.query.labels)

        viewModel.onSearchQueryChange("")
        testScheduler.advanceUntilIdle()
        assertEquals(emptySet<Long>(), viewModel.state.value.query.labels)
    }

    @Test
    fun `a chip selection survives typing in the search box`() = runTest {
        viewModel.selectLabelFilter(7L)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(7L), viewModel.state.value.query.labels)

        viewModel.onSearchQueryChange("milk")
        testScheduler.advanceUntilIdle()

        // The two inputs own separate halves of the query, so a keystroke cannot clear a chip.
        assertEquals(setOf(7L), viewModel.state.value.query.labels)
        assertEquals("milk", viewModel.state.value.query.text)
    }

    /**
     * The near-match fallback is invisible in the results themselves -- a list of near matches
     * looks exactly like a list of matches -- so the flag that lets the UI say so has to be right
     * in both directions. Set when the fallback fired, and cleared again when it did not, or the
     * banner would outlive the typo that caused it.
     */
    @Test
    fun `a typo reports near matches, and a correction clears the flag`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "Recipes", content = "", timestamp = 0L, color = 0),
            Note(id = 2L, title = "Home", content = "", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)
        viewModel = createViewModel()

        viewModel.onSearchQueryChange("recipies")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertTrue(viewModel.state.value.isFuzzyResult)

        viewModel.onSearchQueryChange("recipes")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredNotes.size)
        assertFalse(viewModel.state.value.isFuzzyResult)
    }

    /**
     * A search that genuinely matches nothing is not a near-match result. The empty state reads
     * this to choose between "no results for X" and an explanation of a fallback that never ran.
     */
    @Test
    fun `a search with no near matches is not reported as fuzzy`() = runTest {
        every { repository.getActiveNotes() } returns flowOf(
            listOf(Note(id = 1L, title = "Home", content = "", timestamp = 0L, color = 0))
        )
        viewModel = createViewModel()

        viewModel.onSearchQueryChange("xylophone")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.filteredNotes.isEmpty())
        assertFalse(viewModel.state.value.isFuzzyResult)
    }

    @Test
    fun `unrecognised operators are reported rather than searched for`() = runTest {
        viewModel.onSearchQueryChange("is:sideways")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("is:sideways"), viewModel.state.value.unknownOperators)
        assertEquals("", viewModel.state.value.query.text)
    }

    /**
     * Clearing has to empty the box too. Leaving the text would re-apply its operators on the very
     * next rebuild, so "Clear filters" would visibly do nothing for anyone filtering by typing.
     */
    @Test
    fun `clearing filters also clears the search box`() = runTest {
        val work = Label(id = 42L, name = "Work")
        every { repository.getLabels() } returns flowOf(listOf(work))
        viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("label:work milk")
        viewModel.selectColorFilter(0xFF2E5A32.toInt())
        testScheduler.advanceUntilIdle()

        viewModel.clearFilters()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("", state.searchQuery)
        assertEquals(false, state.query.hasActiveFilters)
    }

    /** Sort and view are durable; the rest of the query is not. */
    @Test
    fun `only sort and view are persisted`() = runTest {
        viewModel.setSortOrder(NoteSortOrder.NEWEST)
        viewModel.setViewMode(NoteViewMode.LIST)
        viewModel.onSearchQueryChange("milk")
        viewModel.selectLabelFilter(3L)
        testScheduler.advanceUntilIdle()

        coVerify { settingsRepository.setNoteSortOrder(NoteSortOrder.NEWEST) }
        coVerify { settingsRepository.setNoteViewMode(NoteViewMode.LIST) }
        coVerify(exactly = 0) { settingsRepository.addRecentSearch(any()) }
    }

}
