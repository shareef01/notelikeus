package com.aus.notelikeus.ui.main

import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope

@OptIn(ExperimentalCoroutinesApi::class)
class NoteActionsControllerTest {

    private val note = Note(id = 1L, title = "Note", content = "Body", timestamp = 1L, color = 0)

    private fun controller(
        repository: FakeNoteRepository,
        state: MutableStateFlow<MainState>,
        hidden: MutableSet<Long>,
        scope: CoroutineScope,
        provider: LocalCommitTokenProvider = LocalCommitTokenProvider { LocalCommitGate.capture(UID) },
    ) = NoteActionsController(
        repository = repository,
        state = state,
        scope = scope,
        // The real gate, so a test can drive a generation change and observe a real stale outcome.
        localCommitTokenProvider = provider,
        hideNotes = { ids -> hidden.addAll(ids) },
        revealNotes = { ids -> hidden.removeAll(ids.toSet()) },
        onListStructureChanged = {}
    )

    @Test
    fun failedArchiveRevealsTheNoteAndReportsTheFailure() = runTest {
        val repository = FakeNoteRepository().apply { failWrites = true }
        val state = MutableStateFlow(MainState(notes = listOf(note), pendingUndoMessage = "staged"))
        val hidden = mutableSetOf<Long>()

        controller(repository, state, hidden, this).archiveNote(note)
        advanceUntilIdle()

        assertTrue(hidden.isEmpty(), "a note the database never archived must not stay hidden")
        assertEquals(NoteActionFailure.UPDATE, state.value.pendingActionFailure)
        assertNull(state.value.pendingUndoMessage)
    }

    @Test
    fun failedPermanentDeleteReportsDeleteFailure() = runTest {
        val repository = FakeNoteRepository().apply { failWrites = true }
        val state = MutableStateFlow(
            MainState(notes = listOf(note), query = NoteQuery(scope = NoteScope.TRASH))
        )
        val hidden = mutableSetOf<Long>()

        controller(repository, state, hidden, this).trashNote(note)
        advanceUntilIdle()

        assertTrue(hidden.isEmpty())
        assertEquals(NoteActionFailure.DELETE, state.value.pendingActionFailure)
    }

    @Test
    fun successfulArchiveLeavesNoFailure() = runTest {
        val repository = FakeNoteRepository()
        val state = MutableStateFlow(MainState(notes = listOf(note)))
        val hidden = mutableSetOf<Long>()

        controller(repository, state, hidden, this).archiveNote(note)
        advanceUntilIdle()

        assertEquals(listOf(1L), hidden.toList())
        assertNull(state.value.pendingActionFailure)
        assertTrue(repository.updatedNotes.single().isArchived)
    }

    // ---- NL-11: a move to Trash is immediate and undoable ----

    @Test
    fun trashingTheSelectionMovesItToTrashAndUndoPutsItBack() = runTest {
        val repository = FakeNoteRepository()
        val state = MutableStateFlow(MainState(notes = listOf(note), selectedNotes = setOf(1L)))
        val hidden = mutableSetOf<Long>()
        val actions = controller(repository, state, hidden, this)

        actions.deleteSelectedNotes()
        advanceUntilIdle()

        val trashed = repository.updatedNotes.single()
        assertTrue(trashed.isTrashed, "the selection was not moved to Trash")
        assertTrue(repository.deletedNotes.isEmpty(), "moving to Trash must not delete anything")
        assertEquals(setOf(1L), hidden)

        actions.undoLastAction()
        advanceUntilIdle()

        val restored = repository.updatedNotes.last()
        assertEquals(1L, restored.id)
        assertTrue(!restored.isTrashed, "Undo did not take the note back out of Trash")
        assertTrue(hidden.isEmpty(), "Undo left the restored note hidden")
        assertNull(state.value.pendingActionFailure)
    }

    @Test
    fun deletingTheSelectionFromTrashIsPermanent() = runTest {
        val repository = FakeNoteRepository()
        val state = MutableStateFlow(
            MainState(
                notes = listOf(note.copy(isTrashed = true)),
                selectedNotes = setOf(1L),
                query = NoteQuery(scope = NoteScope.TRASH),
            )
        )
        val hidden = mutableSetOf<Long>()

        controller(repository, state, hidden, this).deleteSelectedNotes()
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.deletedNotes.map { it.id })
    }

    /** A swipe in Trash runs once the user confirms, deletes for good, and Undo still restores it. */
    @Test
    fun aConfirmedSwipeInTrashDeletesPermanentlyAndUndoRestoresIt() = runTest {
        val repository = FakeNoteRepository()
        val inTrash = note.copy(isTrashed = true)
        val state = MutableStateFlow(
            MainState(notes = listOf(inTrash), query = NoteQuery(scope = NoteScope.TRASH))
        )
        val hidden = mutableSetOf<Long>()
        val actions = controller(repository, state, hidden, this)

        actions.trashNote(inTrash)
        advanceUntilIdle()
        assertEquals(listOf(1L), repository.deletedNotes.map { it.id })

        actions.undoLastAction()
        advanceUntilIdle()
        assertEquals(listOf(1L), repository.insertedNotes.map { it.id }, "Undo did not restore the note")
        assertTrue(hidden.isEmpty(), "Undo left the restored note hidden")
        assertNull(state.value.pendingActionFailure)
    }

    // ---- R15: one originating token per action, and a refusal is not a failure ----

    /**
     * The action's token is taken **once**, at the tap, and a refusal abandons the action instead of
     * committing into the dataset that replaced the one the user was looking at.
     *
     * A second capture anywhere below would hand the continuation whatever generation is current by
     * then, which is exactly the laundering F-5 is about; and a refusal reported as a failure would
     * tell the replacement dataset's user that their action had gone wrong.
     */
    @Test
    fun aStaleActionCapturesOneTokenMutatesNothingAndReportsNoFailure() = runTest {
        var captures = 0
        val provider = LocalCommitTokenProvider {
            captures++
            LocalCommitGate.capture(UID)
        }
        val repository = FakeNoteRepository()
        val state = MutableStateFlow(MainState(notes = listOf(note)))
        val hidden = mutableSetOf<Long>()

        // The tap captures the token; the boundary lands before the action's coroutine runs, which
        // is the production shape: the user acted, and the dataset was replaced before the write.
        controller(repository, state, hidden, this, provider).archiveNote(note)
        LocalCommitGate.isolate { }
        advanceUntilIdle()

        assertEquals(1, captures, "the action captured more than one token")
        assertTrue(repository.updatedNotes.isEmpty(), "a stale action mutated the replacement dataset")
        assertNull(state.value.pendingActionFailure, "a generation refusal was reported as a failure")
        assertTrue(hidden.isEmpty(), "a stale action left the note optimistically hidden")
    }
}

private const val UID = "test-uid"
