package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local durability versus cloud synchronisation.
 *
 * The editor used to upload attachments in the middle of the save: on a new note the upload ran
 * after the Room insert but before the editor adopted the generated id, so a network failure threw
 * past the state update and the next save inserted the note a second time. On an existing note the
 * upload ran *before* `updateNote`, so a network failure meant the user's text never reached Room
 * at all. These pin the ordering that fixes both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorLocalSaveTest {

    private lateinit var repository: NoteRepository
    private lateinit var reminderManager: ReminderManager
    private lateinit var staging: AttachmentStagingStore

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk(relaxed = true)
        reminderManager = mockk(relaxed = true)
        staging = mockk(relaxed = true)
        every { repository.getLabels() } returns flowOf(emptyList())
        coEvery {
            staging.stage(any(), any(), any(), any(), any())
        } answers {
            StagedAttachment(
                attachmentId = firstArg(),
                ownerId = secondArg(),
                noteId = thirdArg(),
                mimeType = "image/png",
                sizeBytes = 3,
                createdAt = 0L,
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Attachments are enabled explicitly: the real flag reads a Worker URL from the build, which
    // is empty in CI, and these tests are specifically about the attachment paths.
    private fun viewModel(sync: AttachmentSyncService? = null) = EditorViewModel(
        repository,
        reminderManager,
        SavedStateHandle(),
        sync,
        attachmentsEnabled = { true },
        localCommitTokenProvider = FakeLocalCommitTokenProvider(),
    )

    /** A sync service whose uploads always fail, standing in for being offline. */
    private fun failingSync(): AttachmentSyncService {
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.syncNoteAttachments(any(), any()) } throws java.io.IOException("offline")
        coEvery { sync.stageAttachment(any(), any(), any(), any()) } returns true
        // Phase 3C.2: the continuation binds staged bytes through the token-aware overload.
        coEvery { sync.bindStagedAttachmentsToNote(any(), any(), any()) } returns
            LocalCommitResult.Applied(Unit)
        return sync
    }

    @Test
    fun `a failed attachment upload does not insert the note twice`() = runTest {
        coEvery { repository.getNextNotePosition() } returns 0
        // Phase 3C.1: the new-note branch commits through the token-aware insert now.
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(7L)
        val subject = viewModel(failingSync())
        advanceUntilIdle()

        subject.onTitleChange("Note with an image")
        subject.addAttachment(byteArrayOf(1, 2, 3), "image/png")
        advanceUntilIdle()

        // First save inserts; the attachment upload behind it fails.
        assertEquals(LocalSaveResult.Saved(7L), subject.saveLocallyAndAwait())
        // The retry must update the row that already exists, not mint another note.
        assertEquals(LocalSaveResult.Saved(7L), subject.saveLocallyAndAwait())

        coVerify(exactly = 1) { repository.insertNoteWithResult(any(), any()) }
        assertEquals(7L, subject.state.value.id)
    }

    @Test
    fun `the editor adopts the generated id even though the upload fails`() = runTest {
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(11L)
        val subject = viewModel(failingSync())
        advanceUntilIdle()

        subject.onTitleChange("Adopts its id")
        subject.addAttachment(byteArrayOf(1, 2, 3), "image/png")
        advanceUntilIdle()
        subject.saveLocallyAndAwait()

        assertEquals(11L, subject.state.value.id)
        // Offline is a pending sync, never a failed save.
        assertTrue(subject.state.value.attachmentSyncPending)
        assertEquals(false, subject.state.value.saveFailed)
    }

    @Test
    fun `an existing note's text reaches Room even when the upload fails`() = runTest {
        val existing = Note(
            id = 3L,
            title = "Before",
            content = "Before body",
            timestamp = 0L,
            color = 0,
        )
        coEvery { repository.getNoteById(3L) } returns existing
        val subject = EditorViewModel(
            repository,
            reminderManager,
            SavedStateHandle(mapOf("noteId" to 3L)),
            failingSync(),
            attachmentsEnabled = { true },
            localCommitTokenProvider = FakeLocalCommitTokenProvider(),
        )
        advanceUntilIdle()

        subject.onTitleChange("Edited while offline")
        subject.addAttachment(byteArrayOf(1, 2, 3), "image/png")
        advanceUntilIdle()

        assertEquals(LocalSaveResult.Saved(3L), subject.saveLocallyAndAwait())

        // The regression this pins: the upload used to run first, so this write never happened.
        // Fenced as of Phase 3B.1: an existing-note manual save commits through the token-aware
        // overload, so asserting the unfenced one would no longer describe the production path.
        coVerify(atLeast = 1) {
            repository.updateNote(
                match { it.id == 3L && it.title == "Edited while offline" },
                any(),
            )
        }
    }

    @Test
    fun `a failed local write is reported as failed and keeps the text in the editor`() = runTest {
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertNoteWithResult(any(), any()) } throws IllegalStateException("disk full")
        val subject = viewModel()
        advanceUntilIdle()

        subject.onTitleChange("Unsaved but still visible")

        val result = subject.saveLocallyAndAwait()

        assertTrue(result is LocalSaveResult.Failed)
        assertTrue(subject.state.value.saveFailed)
        // The editor is the only copy now, so it must still hold the text.
        assertEquals("Unsaved but still visible", subject.state.value.title)
    }

    @Test
    fun `an empty editor reports nothing to save rather than failing`() = runTest {
        val subject = viewModel()
        advanceUntilIdle()

        assertEquals(LocalSaveResult.Unchanged, subject.saveLocallyAndAwait())
        coVerify(exactly = 0) { repository.insertNoteWithResult(any(), any()) }
    }

    @Test
    fun `an attachment is not referenced when its bytes could not be staged`() = runTest {
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.stageAttachment(any(), any(), any(), any()) } returns false
        val subject = viewModel(sync)
        advanceUntilIdle()

        subject.onTitleChange("Staging fails")
        subject.addAttachment(byteArrayOf(1, 2, 3), "image/png")
        advanceUntilIdle()

        // Referencing bytes that were never written is what leaves an attachment pointing at
        // nothing after a restart.
        assertTrue(subject.state.value.attachments.isEmpty())
        assertTrue(subject.state.value.attachmentStagingFailed)
    }

    @Test
    fun `staged bytes are bound to the note id the insert issued`() = runTest {
        coEvery { repository.getNextNotePosition() } returns 0
        coEvery { repository.insertNoteWithResult(any(), any()) } returns LocalCommitResult.Applied(21L)
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.stageAttachment(any(), any(), any(), any()) } returns true
        coEvery { sync.syncNoteAttachments(any(), any()) } answers { LocalCommitResult.Applied(firstArg()) }
        coEvery { sync.bindStagedAttachmentsToNote(any(), any(), any()) } returns
            LocalCommitResult.Applied(Unit)
        val subject = viewModel(sync)
        advanceUntilIdle()

        subject.onTitleChange("Binds its attachment")
        subject.addAttachment(byteArrayOf(1, 2, 3), "image/png")
        advanceUntilIdle()
        subject.saveLocallyAndAwait()

        // Token-aware as of Phase 3C.2: the binding carries the save's originating token.
        coVerify { sync.bindStagedAttachmentsToNote(21L, any(), any()) }
        assertNotNull(subject.state.value.id)
    }

    private fun editorOn(note: Note, sync: AttachmentSyncService? = null): EditorViewModel {
        coEvery { repository.getNoteById(note.id!!) } returns note
        return EditorViewModel(
            repository,
            reminderManager,
            SavedStateHandle(mapOf("noteId" to note.id!!)),
            sync,
            attachmentsEnabled = { true },
            localCommitTokenProvider = FakeLocalCommitTokenProvider(),
        )
    }

    private val storedNote = Note(
        id = 5L,
        title = "Shopping",
        content = "milk",
        timestamp = 1_000L,
        color = 0,
    )

    /**
     * Looking at a note is not editing it. Leaving the editor used to save regardless, which
     * stamped the note with the current time and queued an upload: a note opened and closed jumped
     * to the top of a date-sorted list with a "last edited" time that was never an edit.
     */
    @Test
    fun `opening an existing note and leaving it without a change does not save it`() = runTest {
        val subject = editorOn(storedNote)
        advanceUntilIdle()

        assertEquals(LocalSaveResult.Unchanged, subject.saveLocallyAndAwait())

        coVerify(exactly = 0) { repository.updateNote(any(), any()) }
        coVerify(exactly = 0) { repository.updateNote(any()) }
        assertEquals(1_000L, subject.state.value.timestamp)
    }

    @Test
    fun `an existing note that was edited is saved, and a second save with no further change is not`() = runTest {
        val subject = editorOn(storedNote)
        advanceUntilIdle()

        subject.onTitleChange("Shopping list")
        assertEquals(LocalSaveResult.Saved(5L), subject.saveLocallyAndAwait())
        assertEquals(LocalSaveResult.Unchanged, subject.saveLocallyAndAwait())

        coVerify(exactly = 1) {
            repository.updateNote(match { it.id == 5L && it.title == "Shopping list" }, any())
        }
    }

    @Test
    fun `a change made back to the stored value is not saved either`() = runTest {
        val subject = editorOn(storedNote)
        advanceUntilIdle()

        subject.onTitleChange("Shopping list")
        subject.onTitleChange("Shopping")

        assertEquals(LocalSaveResult.Unchanged, subject.saveLocallyAndAwait())
        coVerify(exactly = 0) { repository.updateNote(any(), any()) }
    }

    /**
     * A save is also how a failed attachment upload is retried, so an unchanged note that still has
     * bytes waiting must go through the save, not be skipped as "nothing changed".
     */
    @Test
    fun `an unchanged note with an attachment still waiting to upload is saved so the upload retries`() = runTest {
        val pending = com.aus.notelikeus.domain.model.Attachment(
            id = "a1",
            noteId = 5L,
            storagePath = "pending:a1",
            mimeType = "image/png",
        )
        val subject = editorOn(storedNote.copy(attachments = listOf(pending)), failingSync())
        advanceUntilIdle()

        assertEquals(LocalSaveResult.Saved(5L), subject.saveLocallyAndAwait())
    }
}
