package com.aus.notelikeus.ui.main

import com.aus.notelikeus.data.sync.FakeNoteRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class NoteActionsControllerTest {

    private val note = Note(id = 1L, title = "Note", content = "Body", timestamp = 1L, color = 0)

    private fun controller(
        repository: FakeNoteRepository,
        state: MutableStateFlow<MainState>,
        hidden: MutableSet<Long>,
        scope: CoroutineScope
    ) = NoteActionsController(
        repository = repository,
        state = state,
        scope = scope,
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
            MainState(notes = listOf(note), currentFilter = NoteFilter.TRASHED)
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
}
