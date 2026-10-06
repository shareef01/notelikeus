package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.AppLog
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CancellationException
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
 *
 * Every write goes through [launchAction]. A failing repository call previously left the note
 * hidden for the rest of the session — the list said "archived", the database did not — with an
 * undo snackbar offering to reverse something that never happened, and the exception itself
 * escaped into the ViewModel scope with nothing recording it.
 */
internal class NoteActionsController(
    private val repository: NoteRepository,
    private val state: MutableStateFlow<MainState>,
    private val scope: CoroutineScope,
    /**
     * Supplies the dataset generation each user action belongs to.
     *
     * Every write below is account-owned local state keyed by a per-device autoincrement note id, so
     * an action whose tap happened in one dataset must not commit into the one that replaced it —
     * `R15` in docs/AUDIT_DEEPSEEK_2026-09-20.md. The token is taken **once per action**, at the tap,
     * and threaded into every repository call that action makes.
     */
    private val localCommitTokenProvider: LocalCommitTokenProvider,
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

    fun clearPendingActionFailure() {
        state.update { it.copy(pendingActionFailure = null) }
    }

    /**
     * Runs a note write, and on failure reveals whatever the action had optimistically hidden,
     * drops the staged undo, and reports [failure] for the UI to show.
     *
     * [block] is handed the hide callback rather than hiding up front because a bulk action only
     * knows which ids it is touching once it has read them out of the state.
     */
    private fun launchAction(
        failure: NoteActionFailure,
        block: suspend (token: LocalCommitToken, hide: (Collection<Long>) -> Unit) -> Unit
    ) {
        // The action boundary: one token, taken at the tap and before the action's coroutine starts,
        // so an isolation landing anywhere between the tap and the DAO refuses the whole action
        // instead of committing it into the dataset that replaced the one the user was looking at.
        val token = localCommitTokenProvider.capture()
        scope.launch {
            val hidden = mutableSetOf<Long>()
            try {
                block(token) { ids ->
                    hidden.addAll(ids)
                    hideNotes(ids)
                }
            } catch (stale: StaleActionException) {
                // The dataset this action belonged to is gone. That is not a failure to report — the
                // replacement dataset is a different library — so the optimistic hide is undone and
                // no user-visible failure is raised.
                revealNotes(hidden)
                pendingUndo = null
                state.update { it.copy(pendingUndoMessage = null) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppLog.warn(TAG, "Note action $failure failed", error)
                revealNotes(hidden)
                pendingUndo = null
                state.update {
                    it.copy(pendingUndoMessage = null, pendingActionFailure = failure)
                }
            }
        }
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
        launchAction(NoteActionFailure.UPDATE) { token, hide ->
            hide(listOf(noteId))
            applyUpdate(token,
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
        val isPermanent = state.value.currentFilter == NoteFilter.TRASHED
        launchAction(if (isPermanent) NoteActionFailure.DELETE else NoteActionFailure.UPDATE) { token, hide ->
            if (isPermanent) {
                pendingUndo = PendingUndo(listOf(note), UndoAction.PERMANENT_DELETE)
                hide(listOf(noteId))
                applyDelete(token, note)
            } else {
                pendingUndo = PendingUndo(listOf(note), UndoAction.TRASH)
                hide(listOf(noteId))
                applyUpdate(token,
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
        launchAction(NoteActionFailure.DELETE) { token, hide ->
            val notesToDelete = state.value.notes.toList()
            if (notesToDelete.isEmpty()) return@launchAction
            pendingUndo = PendingUndo(notesToDelete, UndoAction.PERMANENT_DELETE)
            hide(notesToDelete.mapNotNull { it.id })
            notesToDelete.forEach { note ->
                applyDelete(token, note.copy(timestamp = DateUtils.currentTimeMillis()))
            }
            clearSelection()
        }
    }

    fun deleteSelectedNotes() {
        launchAction(NoteActionFailure.DELETE) { token, hide ->
            val notesToDelete = state.value.notes.filter { it.id in state.value.selectedNotes }
            val type = if (state.value.currentFilter == NoteFilter.TRASHED) {
                UndoAction.PERMANENT_DELETE
            } else {
                UndoAction.TRASH
            }
            pendingUndo = PendingUndo(notesToDelete, type)
            hide(notesToDelete.mapNotNull { it.id })
            notesToDelete.forEach { note ->
                if (state.value.currentFilter == NoteFilter.TRASHED) {
                    applyDelete(token, note.copy(timestamp = DateUtils.currentTimeMillis()))
                } else {
                    applyUpdate(token,
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
        launchAction(NoteActionFailure.UPDATE) { token, hide ->
            val notesToArchive = state.value.notes.filter { it.id in state.value.selectedNotes }
            pendingUndo = PendingUndo(notesToArchive, UndoAction.ARCHIVE)
            hide(notesToArchive.mapNotNull { it.id })
            notesToArchive.forEach { note ->
                applyUpdate(token,
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
        launchAction(NoteActionFailure.UPDATE) { token, _ ->
            val notesToRestore = state.value.notes.filter { it.id in state.value.selectedNotes }
            notesToRestore.forEach { note ->
                applyUpdate(token,
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
        launchAction(NoteActionFailure.UPDATE) { token, _ ->
            val notesToUpdate = state.value.notes.filter { it.id in state.value.selectedNotes }
            notesToUpdate.forEach { note ->
                applyUpdate(token,
                    note.copy(isPinned = pin, timestamp = DateUtils.currentTimeMillis())
                )
            }
            clearSelection()
        }
    }

    fun undoLastAction() {
        val undo = pendingUndo ?: return
        launchAction(NoteActionFailure.UNDO) { token, _ ->
            val restoredIds = undo.notes.mapNotNull { it.id }
            revealNotes(restoredIds)
            when (undo.type) {
                UndoAction.ARCHIVE, UndoAction.TRASH -> {
                    undo.notes.forEach { note ->
                        applyUpdate(token, note.copy(timestamp = DateUtils.currentTimeMillis()))
                    }
                }
                UndoAction.PERMANENT_DELETE -> {
                    undo.notes.forEach { note ->
                        applyRestore(token, note.copy(timestamp = DateUtils.currentTimeMillis()))
                    }
                    onListStructureChanged()
                }
            }
            pendingUndo = null
        }
    }

    /**
     * Runs one account-bound action call, abandoning the whole action when its dataset is gone.
     *
     * [LocalCommitResult.StaleGeneration] is a deliberate account-boundary refusal rather than a
     * persistence failure, so it must not be reported to the replacement dataset as "that didn't
     * work" — and it must travel as itself, not folded into a generic success or failure.
     */
    private fun <T> fenced(result: LocalCommitResult<T>): T = when (result) {
        is LocalCommitResult.Applied -> result.value
        LocalCommitResult.StaleGeneration -> throw StaleActionException()
    }

    private suspend fun applyUpdate(token: LocalCommitToken, note: Note) =
        fenced(repository.updateNote(note, token))

    private suspend fun applyDelete(token: LocalCommitToken, note: Note) =
        fenced(repository.deleteNote(note, token))

    private suspend fun applyRestore(token: LocalCommitToken, note: Note) =
        fenced(repository.restoreNote(note, token))

    /** Carries a generation refusal out of [launchAction]'s block without logging it as a failure. */
    private class StaleActionException : RuntimeException()

    private companion object {
        const val TAG = "NoteActions"
    }
}
