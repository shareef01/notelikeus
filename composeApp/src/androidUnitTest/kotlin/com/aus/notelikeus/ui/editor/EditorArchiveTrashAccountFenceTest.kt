package com.aus.notelikeus.ui.editor

import androidx.lifecycle.SavedStateHandle
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.domain.repository.NoteRepository
import io.mockk.coEvery
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D11 at the archive and trash entry points for an EXISTING note.
 *
 * Both actions optimistically flip editor state before persisting it, so an account-boundary
 * refusal has to undo that flip: the editor must not keep claiming an archive or a trash that was
 * deliberately not written, and must not offer an undo for it either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorArchiveTrashAccountFenceTest {

    private lateinit var repository: NoteRepository
    private lateinit var reminderManager: ReminderManager
    private lateinit var attachmentSync: AttachmentSyncService

    private var fencedCalls = 0
    private var legacyCalls = 0
    private var receivedToken: LocalCommitToken? = null
    private var mutationBlockRuns = 0

    @BeforeTest
    fun setUp() {
        repository = mockk(relaxed = true)
        reminderManager = mockk(relaxed = true)
        attachmentSync = mockk(relaxed = true)
        every { repository.getLabels() } returns flowOf(emptyList())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- AT-1: archive captures before the coroutine launch ----

    @Test
    fun `AT-1 toggleArchive captures the token synchronously, before the launch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubNote(existingNote())
        stubFencedReturning(LocalCommitResult.Applied(Unit))
        val provider = FakeLocalCommitTokenProvider(uid = "uid-A")
        val vm = createViewModel(provider)
        advanceUntilIdle()
        val capturesAfterInit = provider.captureCount

        vm.toggleArchive()

        assertEquals(
            capturesAfterInit + 1,
            provider.captureCount,
            "toggleArchive did not capture the token synchronously",
        )
        assertEquals(0, fencedCalls, "the write ran before the launched coroutine was advanced")

        advanceUntilIdle()

        assertEquals(1, fencedCalls, "the fenced overload was not called exactly once")
        assertEquals(0, legacyCalls, "the legacy overload was used for an existing-note archive")
        assertEquals(provider.lastToken, receivedToken, "a different token reached the repository")
    }

    // ---- AT-2: stale archive rolls back the flip and suppresses the callback ----

    @Test
    fun `AT-2 a stale archive rolls back the flip, suppresses the callback and is not a failure`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            stubNote(existingNote())
            stubFencedIsolatingFirst()
            val vm = createViewModel(FakeLocalCommitTokenProvider(uid = "uid-A"))
            advanceUntilIdle()

            var callbackCount = 0
            vm.toggleArchive { callbackCount++ }
            advanceUntilIdle()

            assertEquals(1, fencedCalls, "the fenced overload was not reached")
            assertEquals(
                0,
                mutationBlockRuns,
                "the stale mutation block executed: the refusal came after the write, not before",
            )
            assertEquals(0, legacyCalls, "the legacy overload was used as a fallback")
            assertEquals(0, callbackCount, "the stale archive invoked onArchived")

            val state = vm.state.value
            assertFalse(
                state.isArchived,
                "the editor kept claiming an archive state that was never persisted",
            )
            assertFalse(state.saveFailed, "the stale archive was reported as a write failure")
            assertFalse(state.isSaving, "the stale archive left the editor saving")

            // Only the archive flag is rolled back; the user's edits are untouched.
            assertEquals("before", state.title, "the archive rollback disturbed the title")
            assertEquals("before body", state.content, "the archive rollback disturbed the content")
        }

    // ---- AT-3: a successful archive invokes the callback exactly once ----

    @Test
    fun `AT-3 a successful archive persists once and invokes the callback once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubNote(existingNote())
        stubFencedReturning(LocalCommitResult.Applied(Unit))
        val vm = createViewModel(FakeLocalCommitTokenProvider(uid = "uid-A"))
        advanceUntilIdle()

        var callbackCount = 0
        vm.toggleArchive { callbackCount++ }
        advanceUntilIdle()

        assertEquals(1, fencedCalls)
        assertEquals(0, legacyCalls)
        assertEquals(1, callbackCount, "the successful archive did not invoke onArchived exactly once")
        assertTrue(vm.state.value.isArchived, "the successful archive did not stay archived")
    }

    // ---- AT-4: stale unarchive restores the archived state ----

    @Test
    fun `AT-4 a stale unarchive restores the archived state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubNote(existingNote().copy(isArchived = true))
        stubFencedIsolatingFirst()
        val vm = createViewModel(FakeLocalCommitTokenProvider(uid = "uid-A"))
        advanceUntilIdle()
        assertTrue(vm.state.value.isArchived, "precondition: the note should start archived")

        vm.toggleArchive()
        advanceUntilIdle()

        assertEquals(0, mutationBlockRuns, "the stale unarchive reached the mutation block")
        assertEquals(0, legacyCalls)
        assertFalse(vm.state.value.saveFailed, "the stale unarchive was reported as a write failure")
        assertTrue(
            vm.state.value.isArchived,
            "the editor kept claiming an unarchive state that was never persisted",
        )
    }

    // ---- AT-5: trash success returns an explicit success result ----

    @Test
    fun `AT-5 trashNoteForDelete returns Trashed with the pre-trash snapshot`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubNote(existingNote())
        stubFencedReturning(LocalCommitResult.Applied(Unit))
        val vm = createViewModel(FakeLocalCommitTokenProvider(uid = "uid-A"))
        advanceUntilIdle()

        val result = vm.trashNoteForDelete()

        val trashed = assertIs<TrashNoteResult.Trashed>(result)
        assertEquals("before", trashed.snapshot.title)
        assertFalse(trashed.snapshot.isTrashed, "the undo snapshot was not a pre-trash note")
        assertEquals(1, fencedCalls, "the fenced overload was not called exactly once")
        assertEquals(0, legacyCalls, "the legacy overload was used for an existing-note trash")
        assertTrue(vm.state.value.isTrashed, "the successful trash did not mark the note trashed")
    }

    // ---- AT-6: stale trash is an explicit account-boundary result ----

    @Test
    fun `AT-6 a stale trash returns AccountChanged and rolls back the trash flag`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubNote(existingNote())
        stubFencedIsolatingFirst()
        val vm = createViewModel(FakeLocalCommitTokenProvider(uid = "uid-A"))
        advanceUntilIdle()

        val result = vm.trashNoteForDelete()

        assertEquals(
            TrashNoteResult.AccountChanged,
            result,
            "the stale trash was not reported as an explicit account-boundary refusal",
        )
        assertEquals(1, fencedCalls, "the fenced overload was not reached")
        assertEquals(0, mutationBlockRuns, "the stale trash reached the mutation block")
        assertEquals(0, legacyCalls, "the legacy overload was used as a fallback")

        val state = vm.state.value
        assertFalse(state.saveFailed, "the stale trash was reported as a write failure")
        assertFalse(state.isSaving, "the stale trash left the editor saving")
        assertFalse(state.isTrashed, "the editor kept claiming a trash that was never persisted")
    }

    // ---- AT-7: an empty editor is still its own distinct outcome ----

    @Test
    fun `AT-7 an empty editor reports NothingToTrash and writes nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubNote(
            Note(id = NOTE_ID, title = "", content = "", timestamp = 1_000L, color = 0),
        )
        stubFencedReturning(LocalCommitResult.Applied(Unit))
        val vm = createViewModel(FakeLocalCommitTokenProvider(uid = "uid-A"))
        advanceUntilIdle()

        assertEquals(
            TrashNoteResult.NothingToTrash,
            vm.trashNoteForDelete(),
            "an empty editor did not report its own outcome",
        )
        assertEquals(0, fencedCalls, "an empty editor wrote to the repository")
        assertEquals(0, legacyCalls, "an empty editor wrote to the repository")
    }

    // ---- fixtures ----

    private fun createViewModel(provider: FakeLocalCommitTokenProvider) = EditorViewModel(
        repository,
        reminderManager,
        SavedStateHandle(mapOf("noteId" to NOTE_ID)),
        attachmentSync,
        attachmentsEnabled = { true },
        localCommitTokenProvider = provider,
    )

    private fun stubNote(note: Note) {
        coEvery { repository.getNoteById(NOTE_ID) } returns note
    }

    private fun stubFencedReturning(result: LocalCommitResult<Unit>) {
        coEvery { repository.updateNote(any<Note>(), any<LocalCommitToken>()) } answers {
            fencedCalls++
            receivedToken = secondArg<LocalCommitToken>()
            result
        }
        coEvery { repository.updateNote(any<Note>()) } answers {
            legacyCalls++
        }
    }

    /** The account boundary lands after the token was captured but before the gate is entered. */
    private fun stubFencedIsolatingFirst() {
        coEvery { repository.updateNote(any<Note>(), any<LocalCommitToken>()) } coAnswers {
            fencedCalls++
            val token = secondArg<LocalCommitToken>()
            receivedToken = token
            LocalCommitGate.isolate { }
            LocalCommitGate.commit(token) {
                mutationBlockRuns++
                Unit
            }
        }
        coEvery { repository.updateNote(any<Note>()) } answers {
            legacyCalls++
        }
    }

    private fun existingNote() = Note(
        id = NOTE_ID,
        title = "before",
        content = "before body",
        timestamp = 1_000L,
        color = 0,
    )

    private companion object {
        const val NOTE_ID = 42L
    }
}
