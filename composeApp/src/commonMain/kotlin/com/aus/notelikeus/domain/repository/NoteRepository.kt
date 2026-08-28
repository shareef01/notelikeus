package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.Label
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun getActiveNotes(): Flow<List<Note>>
    fun getArchivedNotes(): Flow<List<Note>>
    fun getTrashedNotes(): Flow<List<Note>>
    suspend fun getNoteById(id: Long): Note?
    suspend fun insertNote(note: Note)
    suspend fun insertNoteWithResult(note: Note): Long
    /**
     * Inserts [note] without enqueueing a cloud upload. Used inside [withWriteTransaction]
     * so a rolled-back import cannot schedule uploads for rows that never committed.
     */
    suspend fun insertNoteWithoutSync(note: Note): Long
    /**
     * Runs [block] in one writer transaction. Nested calls join the outer transaction.
     */
    suspend fun <R> withWriteTransaction(block: suspend () -> R): R
    /** Reminder + upload + widget refresh after a successful import transaction. */
    suspend fun finalizeImportedNotes(ids: List<Long>)
    /** Re-inserts a previously deleted note and ensures cloud tombstones are cleared. */
    suspend fun restoreNote(note: Note): Long
    suspend fun updateNote(note: Note)
    suspend fun updateNotePositions(notes: List<Note>)
    suspend fun deleteNote(note: Note)
    /** Wipes notes/labels/reminders for account switch. Does not touch the SQLCipher key. */
    suspend fun clearAllUserData()
    suspend fun getNextNotePosition(): Int
    suspend fun getAllNotesForBackup(): List<Note>
    suspend fun getCloudEligibleNoteCount(): Int
    suspend fun getAllLabelsSnapshot(): List<Label>
    suspend fun getNotesWithActiveReminders(now: Long): List<Note>
    suspend fun getNotesWithMissedReminders(now: Long): List<Note>
    suspend fun clearReminderTimestamp(noteId: Long)
    /** Refreshes only the cached sync-conflict clock — see Note.serverUpdatedAt. */
    suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long)
    fun getActiveNoteCount(): Flow<Int>

    fun getLabels(): Flow<List<Label>>
    suspend fun insertLabel(label: Label): Long
    suspend fun updateLabel(label: Label)
    suspend fun deleteLabel(label: Label)
}
