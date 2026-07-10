package com.aus.notelikeus.data.repository

import android.content.Context
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.*
import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.ui.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    private val labelDao: LabelDao,
    private val reminderScheduler: ReminderScheduler,
    @ApplicationContext private val context: Context
) : NoteRepository {

    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun refreshWidget() {
        widgetScope.launch {
            WidgetUpdater.refresh(context)
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

    override suspend fun getNoteByCloudId(cloudId: String): Note? {
        return noteDao.getNoteByCloudId(cloudId)?.toNote()
    }

    override suspend fun insertNote(note: Note) {
        insertNoteWithResult(note)
    }

    override suspend fun insertNoteWithResult(note: Note): Long {
        val noteId = noteDao.insertNote(note.toNoteEntity())
        
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

        // Drop legacy attachment rows; feature archived.
        noteDao.deleteAttachments(noteId)
        syncReminderForNote(note.copy(id = noteId))
        refreshWidget()
        return noteId
    }

    override suspend fun updateNote(note: Note) {
        val noteId = note.id
        noteDao.updateNote(note.toNoteEntity())
        if (noteId == null) return
        
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

        noteDao.deleteAttachments(noteId)
        syncReminderForNote(note)
        refreshWidget()
    }

    override suspend fun updateNotePositions(notes: List<Note>) {
        notes.forEachIndexed { index, note ->
            val noteId = note.id ?: return@forEachIndexed
            if (note.position != index) {
                noteDao.updateNotePosition(noteId, index)
            }
        }
        refreshWidget()
    }

    override suspend fun deleteNote(note: Note) {
        note.id?.let { reminderScheduler.cancelReminder(it) }
        note.id?.let { noteDao.deleteAttachments(it) }
        noteDao.deleteNote(note.toNoteEntity())
        refreshWidget()
    }

    override suspend fun getNextNotePosition(): Int = noteDao.getNextNotePosition()

    override fun getActiveNoteCount(): Flow<Int> = noteDao.getActiveNoteCount()

    override suspend fun getNotesWithActiveReminders(now: Long): List<Note> {
        return noteDao.getNotesWithActiveReminders(now).map { it.toNote() }
    }

    override suspend fun getAllNotesForBackup(): List<Note> {
        return noteDao.getAllNotesForBackup().map { it.toNote() }
    }

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
        labelDao.deleteLabel(label.toLabelEntity())
        refreshWidget()
    }

    private fun syncReminderForNote(note: Note) {
        val noteId = note.id ?: return
        val shouldCancel = note.isTrashed || note.isArchived || note.isLocked || note.reminderTimestamp == null
        if (shouldCancel) {
            reminderScheduler.cancelReminder(noteId)
        } else {
            reminderScheduler.scheduleReminder(
                noteId = noteId,
                title = note.title,
                content = note.content,
                timestamp = note.reminderTimestamp
            )
        }
    }
}
