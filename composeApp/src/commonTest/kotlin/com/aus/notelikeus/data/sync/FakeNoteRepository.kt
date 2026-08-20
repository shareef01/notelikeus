package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [NoteRepository] for testing [NoteSyncEngine].
 *
 * Keeps notes and labels in memory. Only the methods the engine actually
 * calls are implemented; the rest throw [UnsupportedOperationException].
 */
class FakeNoteRepository : NoteRepository {

    private val notes = mutableMapOf<Long, Note>()
    private val labels = mutableListOf<Label>()
    private var nextId: Long = 1L
    private var nextLabelId: Long = 1L

    // Track what the engine asked us to do
    val updatedServerTimestamps = mutableMapOf<Long, Long>()
    val deletedNotes = mutableListOf<Note>()
    val insertedNotes = mutableListOf<Note>()
    val updatedNotes = mutableListOf<Note>()
    val clearedAll = mutableListOf<Boolean>()

    // Simple Flow for active note count
    private val _activeNoteCount = MutableStateFlow(0)

    /** Makes every note write reject, for exercising failure handling. */
    var failWrites = false

    // ---- methods used by NoteSyncEngine ----

    override suspend fun getAllNotesForBackup(): List<Note> = notes.values.toList()

    override suspend fun getNoteById(id: Long): Note? = notes[id]

    override suspend fun insertNoteWithResult(note: Note): Long {
        val id = note.id ?: nextId++
        val saved = note.copy(id = id)
        notes[id] = saved
        insertedNotes.add(saved)
        _activeNoteCount.value = notes.size
        return id
    }

    override suspend fun updateNote(note: Note) {
        if (failWrites) throw IllegalStateException("update failed")
        note.id?.let { id ->
            notes[id] = note
            updatedNotes.add(note)
        }
    }

    override suspend fun deleteNote(note: Note) {
        if (failWrites) throw IllegalStateException("delete failed")
        note.id?.let { notes.remove(it) }
        deletedNotes.add(note)
        _activeNoteCount.value = notes.size
    }

    override suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long) {
        updatedServerTimestamps[noteId] = serverUpdatedAt
        notes[noteId]?.let {
            notes[noteId] = it.copy(serverUpdatedAt = serverUpdatedAt)
        }
    }

    override suspend fun getCloudEligibleNoteCount(): Int = notes.size

    override suspend fun getAllLabelsSnapshot(): List<Label> = labels.toList()

    override suspend fun insertLabel(label: Label): Long {
        val id = label.id ?: nextLabelId++
        val saved = Label(id = id, name = label.name)
        labels.add(saved)
        return id
    }

    override suspend fun clearAllUserData() {
        notes.clear()
        labels.clear()
        clearedAll.add(true)
        _activeNoteCount.value = 0
    }

    // ---- unused by engine (throw) ----

    override fun getActiveNotes(): Flow<List<Note>> = unsupported()
    override fun getArchivedNotes(): Flow<List<Note>> = unsupported()
    override fun getTrashedNotes(): Flow<List<Note>> = unsupported()
    override suspend fun insertNote(note: Note) { insertedNotes.add(note) }
    override suspend fun restoreNote(note: Note): Long = insertNoteWithResult(note)
    override suspend fun updateNotePositions(notes: List<Note>) { unsupported<Unit>() }
    override suspend fun getNextNotePosition(): Int = 0
    override suspend fun getNotesWithActiveReminders(now: Long): List<Note> = emptyList()
    override suspend fun getNotesWithMissedReminders(now: Long): List<Note> = emptyList()
    override suspend fun clearReminderTimestamp(noteId: Long) {}
    override fun getActiveNoteCount(): Flow<Int> = _activeNoteCount
    override fun getLabels(): Flow<List<Label>> = unsupported()
    override suspend fun updateLabel(label: Label) {}
    override suspend fun deleteLabel(label: Label) {}

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("not needed for engine tests")

    // ---- helpers for tests ----

    fun addNote(note: Note) {
        val id = note.id ?: nextId++
        notes[id] = note.copy(id = id)
        _activeNoteCount.value = notes.size
    }

    fun addLabel(label: Label) {
        labels.add(label)
    }
}
