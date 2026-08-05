package com.aus.notelikeus.data.repository

import androidx.room.withTransaction
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.*
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val WIDGET_REFRESH_DEBOUNCE_MS = 250L

class NoteRepositoryImpl(
    private val database: NotelikeusDatabase,
    private val noteDao: NoteDao,
    private val labelDao: LabelDao,
    private val reminderManager: ReminderManager,
    private val widgetManager: PlatformWidgetManager
) : NoteRepository {

    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val widgetRefreshLock = Any()
    private var widgetRefreshJob: Job? = null

    private fun refreshWidget() {
        synchronized(widgetRefreshLock) {
            widgetRefreshJob?.cancel()
            widgetRefreshJob = widgetScope.launch {
                delay(WIDGET_REFRESH_DEBOUNCE_MS)
                widgetManager.refreshWidgets()
            }
        }
    }

    override fun getActiveNotes(): Flow<List<Note>> {
        return noteDao.getActiveNotes().map { entities ->
            entities.map { it.toNote() }
        }
    }

    override fun getArchivedNotes(): Flow<List<Note>> {
        return noteDao.getArchivedNotes().map { entities ->
            entities.map { it.toNote() }
        }
    }

    override fun getTrashedNotes(): Flow<List<Note>> {
        return noteDao.getTrashedNotes().map { entities ->
            entities.map { it.toNote() }
        }
    }

    override suspend fun getNoteById(id: Long): Note? {
        return noteDao.getNoteById(id)?.toNote()
    }

    override suspend fun insertNote(note: Note) {
        insertNoteWithResult(note)
    }

    override suspend fun insertNoteWithResult(note: Note): Long {
        val noteId = database.withTransaction {
            val insertedId = noteDao.insertNote(note.toNoteEntity())

            // Handle labels
            noteDao.deleteNoteLabelCrossRefs(insertedId)
            note.labels.forEach { label ->
                label.id?.let { labelId ->
                    noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(insertedId, labelId))
                }
            }

            // Handle checklists
            noteDao.deleteChecklistItems(insertedId)
            note.checklist.forEach { item ->
                noteDao.insertChecklistItem(item.toChecklistItemEntity(insertedId))
            }

            insertedId
        }
        syncReminderForNote(note.copy(id = noteId))
        refreshWidget()
        return noteId
    }

    override suspend fun updateNote(note: Note) {
        val noteId = note.id ?: return
        database.withTransaction {
            noteDao.updateNote(note.toNoteEntity())

            // Handle labels
            noteDao.deleteNoteLabelCrossRefs(noteId)
            note.labels.forEach { label ->
                label.id?.let { labelId ->
                    noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(noteId, labelId))
                }
            }

            // Handle checklists
            noteDao.deleteChecklistItems(noteId)
            note.checklist.forEach { item ->
                noteDao.insertChecklistItem(item.toChecklistItemEntity(noteId))
            }
        }
        syncReminderForNote(note)
        refreshWidget()
    }

    override suspend fun updateNotePositions(notes: List<Note>) {
        database.withTransaction {
            notes.forEachIndexed { index, note ->
                val noteId = note.id ?: return@forEachIndexed
                if (note.position != index) {
                    // Bump the client timestamp so uploadNote's conflict guard lets the
                    // new position through to the cloud (matches web commitNotePositions).
                    noteDao.updateNotePosition(noteId, index, System.currentTimeMillis())
                }
            }
        }
        refreshWidget()
    }

    override suspend fun deleteNote(note: Note) {
        note.id?.let { reminderManager.cancelReminder(it) }
        database.withTransaction {
            note.id?.let {
                noteDao.deleteNoteLabelCrossRefs(it)
            }
            noteDao.deleteNote(note.toNoteEntity())
        }
        refreshWidget()
    }

    override suspend fun clearAllUserData() {
        val notes = noteDao.getAllNotesForBackup()
        notes.forEach { entity ->
            reminderManager.cancelReminder(entity.note.id)
        }
        database.withTransaction {
            noteDao.deleteAllChecklistItems()
            noteDao.deleteAllNoteLabelCrossRefs()
            noteDao.deleteAllNotes()
            labelDao.deleteAllLabels()
        }
        refreshWidget()
    }

    override suspend fun getNextNotePosition(): Int = noteDao.getNextNotePosition()

    override fun getActiveNoteCount(): Flow<Int> = noteDao.getActiveNoteCount()

    override suspend fun getNotesWithActiveReminders(now: Long): List<Note> {
        return noteDao.getNotesWithActiveReminders(now).map { it.toNote() }
    }

    override suspend fun getNotesWithMissedReminders(now: Long): List<Note> {
        return noteDao.getNotesWithMissedReminders(now).map { it.toNote() }
    }

    override suspend fun clearReminderTimestamp(noteId: Long) {
        noteDao.clearReminderTimestamp(noteId)
        refreshWidget()
    }

    override suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long) {
        noteDao.updateServerTimestamp(noteId, serverUpdatedAt)
    }

    override suspend fun getAllNotesForBackup(): List<Note> {
        return noteDao.getAllNotesForBackup().map { it.toNote() }
    }

    override suspend fun getCloudEligibleNoteCount(): Int = noteDao.getCloudEligibleNoteCount()

    override suspend fun getAllLabelsSnapshot(): List<Label> {
        return labelDao.getAllLabelsOnce().map { it.toLabel() }
    }

    override fun getLabels(): Flow<List<Label>> {
        return labelDao.getAllLabels().map { entities ->
            entities.map { it.toLabel() }
        }
    }

    override suspend fun insertLabel(label: Label): Long {
        val id = labelDao.insertLabel(label.toLabelEntity())
        refreshWidget()
        return id
    }

    override suspend fun updateLabel(label: Label) {
        labelDao.updateLabel(label.toLabelEntity())
        refreshWidget()
    }

    override suspend fun deleteLabel(label: Label) {
        database.withTransaction {
            label.id?.let { labelDao.deleteCrossRefsForLabel(it) }
            labelDao.deleteLabel(label.toLabelEntity())
        }
        refreshWidget()
    }

    private fun syncReminderForNote(note: Note) {
        val noteId = note.id ?: return
        val shouldCancel =
            note.isTrashed || note.isArchived || note.reminderTimestamp == null
        if (shouldCancel) {
            reminderManager.cancelReminder(noteId)
        } else {
            reminderManager.scheduleReminder(
                noteId = noteId,
                timestamp = note.reminderTimestamp!!
            )
        }
    }
}
