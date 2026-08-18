package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Selection-mode note actions and their undo. MainViewModel delegates here so the state
 * machine stays readable; the ViewModel keeps thin wrappers and the UI keeps calling the
 * ViewModel, so no call site changes.
 *
 * The hide/reveal callbacks belong to the ViewModel because hidden ids drive list filtering:
 * an optimistically hidden note is removed from `filteredNotes` until the repository flow
 * confirms the change (or undo reveals it again).
 */
internal class NoteActionsController(
    private val repository: NoteRepository,
    private val state: MutableStateFlow<MainState>,
    private val scope: CoroutineScope,
    private val hideNotes: (Collection<Long>) -> Unit,
    private val revealNotes: (Collection<Long>) -> Unit,
    private val onListStructureChanged: () -> Unit
) {
    private var pendingUndo: PendingUndo? = null

    fun stageEditorUndo(note: Note, type: UndoAction, message: String) {
        pendingUndo = PendingUndo(listOf(note), type)
        state.update { it.copy(pendingUndoMessage = message) }
    }

    fun clearPendingUndoMessage() {
        state.update { it.copy(pendingUndoMessage = null) }
    }

    fun toggleNoteSelection(noteId: Long) {
        state.update { currentState ->
            val newSelection = if (currentState.selectedNotes.contains(noteId)) {
                currentState.selectedNotes - noteId
            } else {
                currentState.selectedNotes + noteId
            }
            currentState.copy(selectedNotes = newSelection)
        }
    }

    fun toggleSelectAll() {
        val visibleIds = state.value.filteredNotes.mapNotNull { it.id }.toSet()
        if (visibleIds.isEmpty()) return
        state.update { currentState ->
            val allSelected = visibleIds.all { it in currentState.selectedNotes }
            currentState.copy(
                selectedNotes = if (allSelected) emptySet() else visibleIds
            )
        }
    }

    fun clearSelection() {
        state.update { it.copy(selectedNotes = emptySet()) }
    }

    fun archiveNote(note: Note) {
        val noteId = note.id ?: return
        pendingUndo = PendingUndo(listOf(note), UndoAction.ARCHIVE)
        hideNotes(listOf(noteId))
        scope.launch {
            repository.updateNote(
                note.copy(
                    isArchived = true,
                    isTrashed = false,
                    timestamp = DateUtils.currentTimeMillis()
                )
            )
        }
    }

    fun trashNote(note: Note) {
        val noteId = note.id ?: return
        scope.launch {
            if (state.value.currentFilter == NoteFilter.TRASHED) {
                pendingUndo = PendingUndo(listOf(note), UndoAction.PERMANENT_DELETE)
                hideNotes(listOf(noteId))
                repository.deleteNote(note)
            } else {
                pendingUndo = PendingUndo(listOf(note), UndoAction.TRASH)
                hideNotes(listOf(noteId))
                repository.updateNote(
                    note.copy(
                        isTrashed = true,
                        isArchived = false,
                        timestamp = DateUtils.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun emptyTrash() {
        if (state.value.currentFilter != NoteFilter.TRASHED) return
        scope.launch {
            val notesToDelete = state.value.notes.toList()
            if (notesToDelete.isEmpty()) return@launch
            pendingUndo = PendingUndo(notesToDelete, UndoAction.PERMANENT_DELETE)
            hideNotes(notesToDelete.mapNotNull { it.id })
            notesToDelete.forEach { note ->
                repository.deleteNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
            }
            clearSelection()
        }
    }

    fun deleteSelectedNotes() {
        scope.launch {
            val notesToDelete = state.value.notes.filter { it.id in state.value.selectedNotes }
            val type = if (state.value.currentFilter == NoteFilter.TRASHED) {
                UndoAction.PERMANENT_DELETE
            } else {
                UndoAction.TRASH
            }
            pendingUndo = PendingUndo(notesToDelete, type)
            hideNotes(notesToDelete.mapNotNull { it.id })
            notesToDelete.forEach { note ->
                if (state.value.currentFilter == NoteFilter.TRASHED) {
                    repository.deleteNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
                } else {
                    repository.updateNote(
                        note.copy(
                            isTrashed = true,
                            isArchived = false,
                            timestamp = DateUtils.currentTimeMillis()
                        )
                    )
                }
            }
            clearSelection()
        }
    }

    fun archiveSelectedNotes() {
        scope.launch {
            val notesToArchive = state.value.notes.filter { it.id in state.value.selectedNotes }
            pendingUndo = PendingUndo(notesToArchive, UndoAction.ARCHIVE)
            hideNotes(notesToArchive.mapNotNull { it.id })
            notesToArchive.forEach { note ->
                repository.updateNote(
                    note.copy(
                        isArchived = true,
                        isTrashed = false,
                        timestamp = DateUtils.currentTimeMillis()
                    )
                )
            }
            clearSelection()
        }
    }

    // Both of these bump `timestamp`, like every other write in this file. A local edit does not
    // move serverUpdatedAt, so once a note has synced, the client timestamp is the only thing
    // separating the two sides -- and cloudWinsConflict resolves an exact tie in the cloud's
    // favour. Leaving it unchanged meant uploadNote skipped the upload *and* the next download
    // overwrote the row, so restoring or pinning a synced note silently undid itself.
    fun restoreSelectedNotes() {
        scope.launch {
            val notesToRestore = state.value.notes.filter { it.id in state.value.selectedNotes }
            notesToRestore.forEach { note ->
                repository.updateNote(
                    note.copy(
                        isArchived = false,
                        isTrashed = false,
                        timestamp = DateUtils.currentTimeMillis()
                    )
                )
            }
            clearSelection()
        }
    }

    fun setSelectedNotesPinned(pin: Boolean) {
        scope.launch {
            val notesToUpdate = state.value.notes.filter { it.id in state.value.selectedNotes }
            notesToUpdate.forEach { note ->
                repository.updateNote(
                    note.copy(isPinned = pin, timestamp = DateUtils.currentTimeMillis())
                )
            }
            clearSelection()
        }
    }

    fun undoLastAction() {
        val undo = pendingUndo ?: return
        scope.launch {
            val restoredIds = undo.notes.mapNotNull { it.id }
            revealNotes(restoredIds)
            when (undo.type) {
                UndoAction.ARCHIVE, UndoAction.TRASH -> {
                    undo.notes.forEach { note ->
                        repository.updateNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
                    }
                }
                UndoAction.PERMANENT_DELETE -> {
                    undo.notes.forEach { note ->
                        repository.restoreNote(note.copy(timestamp = DateUtils.currentTimeMillis()))
                    }
                    onListStructureChanged()
                }
            }
            pendingUndo = null
        }
    }
}
