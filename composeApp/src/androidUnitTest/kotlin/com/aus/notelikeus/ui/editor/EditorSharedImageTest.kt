package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EditorSharedImageTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<NoteRepository>(relaxed = true)
    private val reminderManager = mockk<ReminderManager>(relaxed = true)
    private val staging = mockk<AttachmentStagingStore>(relaxed = true)
    private val syncManager = mockk<SyncManager>(relaxed = true)
    private val accountFlow = MutableStateFlow(CloudAccount(email = "a@example.com"))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { repository.getLabels() } returns flowOf(emptyList())
        every { syncManager.syncStatus } returns MutableStateFlow(CloudSyncStatus.Synced)
        every { syncManager.cloudAccount } returns accountFlow
        coEvery {
            staging.stage(any(), any(), any(), any(), any())
        } answers {
            StagedAttachment(
                attachmentId = firstArg(),
                ownerId = secondArg(),
                noteId = thirdArg(),
                mimeType = "image/png",
                sizeBytes = 4,
                createdAt = 0L,
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        noteId: Long? = null,
        sync: AttachmentSyncService? = null,
    ): EditorViewModel {
        val handle = SavedStateHandle().apply {
            if (noteId != null) set("noteId", noteId)
        }
        val attachmentSyncMock = sync ?: mockk<AttachmentSyncService>(relaxed = true).also {
            coEvery { it.stageAttachment(any(), any(), any(), any(), any()) } returns true
            coEvery { it.syncNoteAttachments(any()) } answers { firstArg() }
        }
        return EditorViewModel(
            repository = repository,
            reminderManager = reminderManager,
            savedStateHandle = handle,
            attachmentSync = attachmentSyncMock,
            attachmentsEnabled = { true },
            syncManager = syncManager,
        )
    }

    @Test
    fun `setInitialSharedImage stages attachment and populates title and content`() = runTest {
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.stageAttachment(any(), any(), any(), any(), any()) } returns true
        coEvery { sync.syncNoteAttachments(any()) } answers { firstArg() }
        val vm = viewModel(sync = sync)
        advanceUntilIdle()

        vm.setInitialSharedImage(
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/png",
            title = "Shared Title",
            content = "Shared Content",
            originatingOwnerId = "user_a",
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Shared Title", state.title)
        assertEquals("Shared Content", state.content)
        assertEquals(1, state.attachments.size)
        assertEquals("image/png", state.attachments[0].mimeType)
        assertTrue(state.attachments[0].storagePath.startsWith("pending:"))
        coVerify(exactly = 1) { sync.stageAttachment(any(), null, any(), "image/png", "user_a") }
    }

    @Test
    fun `setInitialSharedImage rejects attachment if account changed during async ingestion`() = runTest {
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.stageAttachment(any(), any(), any(), any(), "user_b") } returns false
        coEvery { sync.syncNoteAttachments(any()) } answers { firstArg() }
        val vm = viewModel(sync = sync)
        advanceUntilIdle()

        // Account is user_a in syncManager, but share originated from user_b
        vm.setInitialSharedImage(
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/png",
            title = "Title",
            content = "Body",
            originatingOwnerId = "user_b",
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.attachmentStagingFailed)
        assertEquals(0, state.attachments.size)
        coVerify(exactly = 1) { sync.stageAttachment(any(), any(), any(), any(), "user_b") }
    }

    @Test
    fun `setInitialSharedImage is ignored if editor is on an existing note`() = runTest {
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.syncNoteAttachments(any()) } answers { firstArg() }
        coEvery { repository.getNoteById(42L) } returns Note(
            id = 42L,
            title = "Existing",
            content = "Original",
            timestamp = 100L,
            position = 0,
            color = 0,
        )
        val vm = viewModel(noteId = 42L, sync = sync)
        advanceUntilIdle()

        vm.setInitialSharedImage(
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/png",
            title = "New Title",
            content = "New Body",
            originatingOwnerId = "user_a",
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Existing", state.title)
        assertEquals("Original", state.content)
        assertEquals(0, state.attachments.size)
        coVerify(exactly = 0) { sync.stageAttachment(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saveLocallyAndAwait persists note with only shared image and no text`() = runTest {
        coEvery { repository.getNextNotePosition() } returns 1
        coEvery { repository.insertNoteWithResult(any()) } returns 88L
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.stageAttachment(any(), any(), any(), any(), any()) } returns true
        coEvery { sync.syncNoteAttachments(any()) } answers { firstArg() }
        val vm = viewModel(sync = sync)
        advanceUntilIdle()

        vm.setInitialSharedImage(
            bytes = byteArrayOf(9, 8, 7),
            mimeType = "image/jpeg",
            originatingOwnerId = "user_a",
        )
        advanceUntilIdle()

        val result = vm.saveLocallyAndAwait()
        assertTrue(result is LocalSaveResult.Saved)
        assertEquals(88L, (result as LocalSaveResult.Saved).noteId)
        coVerify(exactly = 1) { repository.insertNoteWithResult(match { it.attachments.size == 1 }) }
    }

    @Test
    fun `discardUnsavedChanges on shared image note releases staged attachment`() = runTest {
        val sync = mockk<AttachmentSyncService>(relaxed = true)
        coEvery { sync.stageAttachment(any(), any(), any(), any(), any()) } returns true
        coEvery { sync.syncNoteAttachments(any()) } answers { firstArg() }
        val vm = viewModel(sync = sync)
        testScheduler.runCurrent()

        vm.setInitialSharedImage(
            bytes = byteArrayOf(1, 2, 3),
            mimeType = "image/png",
            originatingOwnerId = "user_a",
        )
        testScheduler.runCurrent()

        vm.discardUnsavedChanges()
        testScheduler.runCurrent()

        coVerify(exactly = 1) { sync.releaseStagedAttachments(match { it.size == 1 }) }
    }
}
