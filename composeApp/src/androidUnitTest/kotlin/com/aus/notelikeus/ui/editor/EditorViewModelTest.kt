package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.NoteRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.compose.ui.text.input.TextFieldValue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var viewModel: EditorViewModel
    private lateinit var repository: NoteRepository
    private lateinit var reminderManager: ReminderManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        reminderManager = mockk(relaxed = true)
        every { repository.getLabels() } returns flowOf(emptyList())
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle): EditorViewModel {
        return EditorViewModel(
            repository,
            reminderManager,
            savedStateHandle,
            localCommitTokenProvider = FakeLocalCommitTokenProvider(),
        )
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
            val state = awaitItem()
            assertEquals("Old Title", state.title)
            assertEquals("Old Content", state.content)
            assertEquals(true, state.isNoteLoaded)
        }
    }

    /**
     * The editor's exit paths (Android back, desktop note-window close) navigate or dispose
     * immediately after saving, which clears the ViewModel and cancels `viewModelScope`.
     * `saveNote()` only *launches* the write into that scope, so the write was racing its own
     * cancellation — and on desktop, where the window leaves composition in the same frame, it
     * lost that race and the note was discarded. That was the reported broken + button.
     *
     * A StandardTestDispatcher is what makes the difference observable: the suite's default
     * UnconfinedTestDispatcher runs launched coroutines eagerly, so both variants look identical
     * and the bug stays invisible.
     */
    @Test
    fun `saveNoteAndAwait persists before it returns`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { repository.getNextNotePosition() } returns 0
        // New-note insertion is account-generation fenced as of Phase 3C.1, so the token-aware
        // overload is the one this path reaches.
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(7L)

        viewModel = createViewModel(SavedStateHandle())
        testScheduler.advanceUntilIdle()
        viewModel.onTitleChange("Survives the close")

        viewModel.saveNoteAndAwait()

        // No advanceUntilIdle() in between: the row must already be committed when the call
        // returns, because the caller disposes the ViewModel on the very next line.
        coVerify(exactly = 1) { repository.insertNoteWithResult(any(), any()) }
    }

    @Test
    fun `saveNote alone has not written by the time it returns`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(7L)

        viewModel = createViewModel(SavedStateHandle())
        testScheduler.advanceUntilIdle()
        viewModel.onTitleChange("Racy")

        viewModel.saveNote()

        // Documents why the exit paths must not use this variant: the write is still queued, so
        // anything that cancels viewModelScope now loses it.
        coVerify(exactly = 0) { repository.insertNoteWithResult(any(), any()) }
    }

    /**
     * `init { loadNote() }` finishes with `_state.value = EditorState(...)` — a *whole state
     * replacement*, not a merge — and it gets there through a suspending settings read. Anything
     * typed before that read completes is therefore overwritten by the blank initial state, and
     * the autosave that follows persists the blank over whatever was already saved.
     *
     * The editor auto-focuses the body for a new note, so typing immediately is the normal way to
     * use it, not an edge case.
     */
    @Test
    fun `text typed before the initial load completes is not wiped`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(1L)

        // Created but not yet run: the load is queued behind the dispatcher, exactly as it is
        // queued behind a DataStore read in the real app.
        viewModel = createViewModel(SavedStateHandle())

        viewModel.onContentValueChange(TextFieldValue("the note I just wrote"))
        viewModel.onTitleChange("my title")

        testScheduler.advanceUntilIdle()

        assertEquals("the note I just wrote", viewModel.state.value.content)
        assertEquals("my title", viewModel.state.value.title)
    }

    @Test
    fun `typing before an existing note loads keeps the text and still fills the metadata`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stored = Note(
            id = 1L,
            title = "Stored title",
            content = "Stored body",
            timestamp = 0L,
            color = 7,
            isPinned = true
        )
        coEvery { repository.getNoteById(1L) } returns stored

        viewModel = createViewModel(SavedStateHandle(mapOf("noteId" to 1L)))
        viewModel.onContentValueChange(TextFieldValue("typed before the load returned"))
        testScheduler.advanceUntilIdle()

        // The user's text survives...
        assertEquals("typed before the load returned", viewModel.state.value.content)
        // ...and the fields they did not touch still come from the stored note, so the editor is
        // fully populated rather than half-empty.
        assertEquals("Stored title", viewModel.state.value.title)
        assertEquals(1L, viewModel.state.value.id)
        assertTrue(viewModel.state.value.isPinned)
    }

    @Test
    fun `onTitleChange updates state`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            awaitItem() // initial
            viewModel.onTitleChange("New Title")
            assertEquals("New Title", awaitItem().title)
        }
    }

    @Test
    fun `onTitleChange clamps to backup importer title cap`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.onTitleChange("t".repeat(NoteBackupImporter.MAX_FIELD_CHARS + 50))
        assertEquals(NoteBackupImporter.MAX_FIELD_CHARS, viewModel.state.value.title.length)
        assertTrue(viewModel.state.value.truncatedToSyncLimit)
    }

    @Test
    fun `onContentValueChange clamps to backup importer content cap`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.onContentValueChange(
            TextFieldValue("c".repeat(NoteBackupImporter.MAX_CONTENT_CHARS + 80)),
        )
        assertEquals(NoteBackupImporter.MAX_CONTENT_CHARS, viewModel.state.value.content.length)
        assertTrue(viewModel.state.value.truncatedToSyncLimit)
    }

    @Test
    fun `togglePin updates state`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("noteId" to -1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(false, initialState.isPinned)
            
            viewModel.togglePin()
            assertEquals(true, awaitItem().isPinned)
        }
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
        // trashNoteForDelete is fenced now, so the token-aware overload is the one it reaches.
        coEvery { repository.updateNote(any(), any()) } returns LocalCommitResult.Applied(Unit)

        val savedStateHandle = SavedStateHandle(mapOf("noteId" to 1L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            awaitItem()
            val result = viewModel.trashNoteForDelete()
            assertEquals("Title", (result as TrashNoteResult.Trashed).snapshot.title)
            assertEquals(true, awaitItem().isTrashed)
            // trashNoteForDelete also persists the note, which bumps the timestamp
            // in a follow-up emission — drain it instead of asserting on it.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `missing note sets noteNotFound`() = runTest {
        coEvery { repository.getNoteById(99L) } returns null

        val savedStateHandle = SavedStateHandle(mapOf("noteId" to 99L))
        viewModel = createViewModel(savedStateHandle)

        viewModel.state.test {
            val state = awaitItem()
            while (!state.isNoteLoaded) {
                awaitItem()
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
    fun `applyBold before an existing note loads keeps the formatted text`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stored = Note(
            id = 1L,
            title = "Stored title",
            content = "Stored body",
            timestamp = 0L,
            color = 0
        )
        coEvery { repository.getNoteById(1L) } returns stored

        viewModel = createViewModel(SavedStateHandle(mapOf("noteId" to 1L)))
        viewModel.onContentValueChange(TextFieldValue("hello", selection = androidx.compose.ui.text.TextRange(0, 5)))
        viewModel.applyBoldToSelection()
        testScheduler.advanceUntilIdle()

        assertEquals("**hello**", viewModel.state.value.content)
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

    // ---- a remote change to the open note ----

    private val openedNote = Note(
        id = 1L, title = "Groceries", content = "milk", timestamp = 900L, color = 0, serverUpdatedAt = 1_000L,
    )

    @Test
    fun `typing against a version another device replaced is saved as a conflicted copy`() = runTest {
        val replaced = openedNote.copy(content = "milk, eggs (from phone)", timestamp = 1_900L, serverUpdatedAt = 2_000L)
        coEvery { repository.getNoteById(1L) } returnsMany listOf(openedNote, replaced)
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(42L)
        coEvery { repository.updateNote(any(), any()) } returns LocalCommitResult.Applied(Unit)
        viewModel = createViewModel(SavedStateHandle(mapOf("noteId" to 1L)))
        advanceUntilIdle()

        viewModel.onContentValueChange(TextFieldValue("milk, bread (typed here)"))
        viewModel.saveNoteAndAwait()

        // The row keeps the other device's version; the draft lands beside it.
        coVerify(exactly = 0) { repository.updateNote(any(), any()) }
        coVerify(exactly = 1) {
            repository.insertNoteWithResult(
                match { it.id == null && it.content == "milk, bread (typed here)" && it.title == "Groceries (conflicted copy)" },
                any(),
            )
        }
        assertTrue(viewModel.state.value.conflictCopy)
        assertEquals(42L, viewModel.state.value.id)
    }

    @Test
    fun `a conflicted copy takes the draft's unuploaded image, staged again under a new id`() = runTest {
        val image = Attachment(id = "att-1", noteId = 1L, storagePath = pendingStoragePath("att-1"))
        val opened = openedNote.copy(attachments = listOf(image))
        val replaced = openedNote.copy(content = "milk, eggs (from phone)", timestamp = 1_900L, serverUpdatedAt = 2_000L)
        val restaged = image.copy(id = "att-copy", noteId = 0L, storagePath = pendingStoragePath("att-copy"))
        val attachmentSync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { attachmentSync.stageForCopy(listOf(image)) } returns mapOf("att-1" to restaged)
        coEvery { attachmentSync.bindStagedAttachmentsToNote(any(), any(), any()) } returns LocalCommitResult.Applied(Unit)
        coEvery { attachmentSync.syncNoteAttachments(any(), any()) } answers { LocalCommitResult.Applied(firstArg()) }
        coEvery { repository.getNoteById(1L) } returnsMany listOf(opened, replaced)
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(42L)
        viewModel = EditorViewModel(
            repository,
            reminderManager,
            SavedStateHandle(mapOf("noteId" to 1L)),
            attachmentSync = attachmentSync,
            localCommitTokenProvider = FakeLocalCommitTokenProvider(),
        )
        advanceUntilIdle()

        viewModel.onContentValueChange(TextFieldValue("milk, bread (typed here)"))
        viewModel.saveNoteAndAwait()

        coVerify(exactly = 1) {
            repository.insertNoteWithResult(match { it.attachments == listOf(restaged) }, any())
        }
        assertEquals(listOf(restaged.copy(noteId = 42L)), viewModel.state.value.attachments)
        coVerify { attachmentSync.bindStagedAttachmentsToNote(42L, listOf(restaged.copy(noteId = 42L)), any()) }
    }

    @Test
    fun `this device's own upload echoing back is still a straight edit`() = runTest {
        // The upload stamped the row with a newer server time; the words are the ones saved here.
        val echoed = openedNote.copy(serverUpdatedAt = 2_000L)
        coEvery { repository.getNoteById(1L) } returnsMany listOf(openedNote, echoed)
        coEvery { repository.updateNote(any(), any()) } returns LocalCommitResult.Applied(Unit)
        viewModel = createViewModel(SavedStateHandle(mapOf("noteId" to 1L)))
        advanceUntilIdle()

        viewModel.onContentValueChange(TextFieldValue("milk, more"))
        viewModel.saveNoteAndAwait()

        coVerify(exactly = 1) { repository.updateNote(match { it.id == 1L && it.content == "milk, more" }, any()) }
        coVerify(exactly = 0) { repository.insertNoteWithResult(any(), any()) }
        assertEquals(false, viewModel.state.value.conflictCopy)
    }

    // ---- the open note changing underneath the editor (AU-12) ----

    private fun openWithLiveRow(initial: Note): kotlinx.coroutines.flow.MutableStateFlow<List<Note>> {
        val active = kotlinx.coroutines.flow.MutableStateFlow(listOf(initial))
        coEvery { repository.getNoteById(initial.id!!) } returns initial
        every { repository.getActiveNotes() } returns active
        every { repository.getArchivedNotes() } returns flowOf(emptyList())
        every { repository.getTrashedNotes() } returns flowOf(emptyList())
        coEvery { repository.updateNote(any(), any()) } returns LocalCommitResult.Applied(Unit)
        viewModel = createViewModel(SavedStateHandle(mapOf("noteId" to initial.id!!)))
        return active
    }

    @Test
    fun `an open note with nothing unsaved shows the newer version a download wrote`() = runTest {
        val rows = openWithLiveRow(openedNote)
        advanceUntilIdle()

        rows.value = listOf(openedNote.copy(content = "milk, eggs (from phone)", timestamp = 1_900L, serverUpdatedAt = 2_000L))
        advanceUntilIdle()

        assertEquals("milk, eggs (from phone)", viewModel.state.value.content)
        // Showing it is not an edit: nothing is written back.
        coVerify(exactly = 0) { repository.updateNote(any(), any()) }
    }

    @Test
    fun `unsaved typing is never replaced by a newer version`() = runTest {
        val rows = openWithLiveRow(openedNote)
        advanceUntilIdle()
        viewModel.onContentValueChange(TextFieldValue("milk, bread (typed here)"))

        rows.value = listOf(openedNote.copy(content = "milk, eggs (from phone)", timestamp = 1_900L, serverUpdatedAt = 2_000L))
        advanceUntilIdle()

        assertEquals("milk, bread (typed here)", viewModel.state.value.content)
    }

    @Test
    fun `this device's own echo changes nothing on screen`() = runTest {
        val rows = openWithLiveRow(openedNote)
        advanceUntilIdle()
        val before = viewModel.state.value

        rows.value = listOf(openedNote.copy(serverUpdatedAt = 2_000L))
        advanceUntilIdle()

        assertEquals(before.content, viewModel.state.value.content)
        assertEquals(before.title, viewModel.state.value.title)
    }
}
