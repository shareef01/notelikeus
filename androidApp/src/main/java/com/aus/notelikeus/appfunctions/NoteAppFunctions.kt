package com.aus.notelikeus.appfunctions

import androidx.appfunctions.service.AppFunction
import androidx.appfunctions.AppFunctionContext
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.data.remote.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Exposes note management capabilities to system agents.
 */
class NoteAppFunctions @Inject constructor(
    private val repository: NoteRepository,
    private val reminderScheduler: ReminderScheduler
) {

    /**
     * Create a new note with a title and content.
     *
     * @param context The execution context.
     * @param title The title for the new note.
     * @param content The body text of the note.
     * @return The newly created [AppFunctionNote] including its system-assigned ID.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createNote(
        context: AppFunctionContext,
        title: String,
        content: String
    ): AppFunctionNote = withContext(Dispatchers.IO) {
        val note = Note(
            title = title,
            content = content,
            timestamp = System.currentTimeMillis(),
            color = 0
        )
        val id = repository.insertNoteWithResult(note)
        AppFunctionNote(
            id = id,
            title = title,
            content = content,
            isPinned = false,
            isArchived = false,
            reminderTimestamp = null
        )
    }

    /**
     * List all active notes.
     * Required workflow: Call this to obtain valid note IDs before calling "addReminder" or "archiveNote" if you don't have a specific search query.
     *
     * @param context The execution context.
     * @return A list of all active [AppFunctionNote] objects.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listNotes(
        context: AppFunctionContext
    ): List<AppFunctionNote> = withContext(Dispatchers.IO) {
        repository.getActiveNotes().first()
            .map { it.toAppFunctionNote() }
    }

    /**
     * Search for active notes by title or content matching a query.
     * Required workflow: Call this to obtain valid note IDs before calling "addReminder" or "archiveNote".
     *
     * @param context The execution context.
     * @param query The search string to match against note title or body.
     * @return A list of matching [AppFunctionNote] objects.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun searchNotes(
        context: AppFunctionContext,
        query: String
    ): List<AppFunctionNote> = withContext(Dispatchers.IO) {
        repository.getActiveNotes().first()
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                it.content.contains(query, ignoreCase = true)
            }
            .map { it.toAppFunctionNote() }
    }

    /**
     * Add or update a time-based reminder for an existing note.
     * Required workflow: Call "searchNotes" first to obtain the correct note ID.
     *
     * @param context The execution context.
     * @param noteId The unique identifier of the note to remind the user about.
     * @param timestamp The time for the reminder, in milliseconds since the epoch. Must be in the future.
     * @return The updated [AppFunctionNote], or null if the [noteId] was not found.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun addReminder(
        context: AppFunctionContext,
        noteId: Long,
        timestamp: Long
    ): AppFunctionNote? = withContext(Dispatchers.IO) {
        val note = repository.getNoteById(noteId) ?: return@withContext null
        val updatedNote = note.copy(reminderTimestamp = timestamp)
        repository.updateNote(updatedNote)
        reminderScheduler.scheduleReminder(noteId, timestamp)
        updatedNote.toAppFunctionNote()
    }

    /**
     * Archive an existing note to remove it from the main list.
     * Required workflow: Call "searchNotes" first to obtain the correct note ID.
     *
     * @param context The execution context.
     * @param noteId The unique identifier of the note to archive.
     * @return The updated [AppFunctionNote] with its archived status set, or null if the [noteId] was not found.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun archiveNote(
        context: AppFunctionContext,
        noteId: Long
    ): AppFunctionNote? = withContext(Dispatchers.IO) {
        val note = repository.getNoteById(noteId) ?: return@withContext null
        val updatedNote = note.copy(isArchived = true)
        repository.updateNote(updatedNote)
        updatedNote.toAppFunctionNote()
    }

    private fun Note.toAppFunctionNote() = AppFunctionNote(
        id = id ?: -1L,
        title = title,
        content = content,
        isPinned = isPinned,
        isArchived = isArchived,
        reminderTimestamp = reminderTimestamp
    )
}
