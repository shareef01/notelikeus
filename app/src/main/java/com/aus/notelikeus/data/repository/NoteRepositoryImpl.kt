package com.aus.notelikeus.data.repository

import android.content.Context
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.*
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

        // Handle attachments
        noteDao.deleteAttachments(noteId)
        note.attachments.forEach { attachment ->
            noteDao.insertAttachment(attachment.toAttachmentEntity(noteId))
        }
        refreshWidget()
        return noteId
    }

    override suspend fun updateNote(note: Note) {
        noteDao.updateNote(note.toNoteEntity())
        val noteId = note.id ?: return
        
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

        // Handle attachments
        noteDao.deleteAttachments(noteId)
        note.attachments.forEach { attachment ->
            noteDao.insertAttachment(attachment.toAttachmentEntity(noteId))
        }
        refreshWidget()
    }

    override suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note.toNoteEntity())
        refreshWidget()
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

    override suspend fun deleteLabel(label: Label) {
        labelDao.deleteLabel(label.toLabelEntity())
        refreshWidget()
    }
}
