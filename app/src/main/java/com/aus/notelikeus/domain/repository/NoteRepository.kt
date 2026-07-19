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
    suspend fun updateNote(note: Note)
    suspend fun updateNotePositions(notes: List<Note>)
    suspend fun deleteNote(note: Note)
    /** Wipes notes/labels/reminders for account switch. Does not touch the SQLCipher key. */
    suspend fun clearAllUserData()
    suspend fun getNextNotePosition(): Int
    suspend fun getAllNotesForBackup(): List<Note>
    suspend fun getAllLabelsSnapshot(): List<Label>
    suspend fun getNotesWithActiveReminders(now: Long): List<Note>
    suspend fun getNotesWithMissedReminders(now: Long): List<Note>
    suspend fun clearReminderTimestamp(noteId: Long)
    fun getActiveNoteCount(): Flow<Int>

    fun getLabels(): Flow<List<Label>>
    suspend fun insertLabel(label: Label): Long
    suspend fun updateLabel(label: Label)
    suspend fun deleteLabel(label: Label)
}
