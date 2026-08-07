package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.local.model.NoteWithLabels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeNoteDao : NoteDao {
    val notes = mutableMapOf<Long, NoteEntity>()
    val checklistItems = mutableMapOf<Long, MutableList<ChecklistItemEntity>>()
    val crossRefs = mutableListOf<NoteLabelCrossRef>()
    var nextId = 1L

    val updatedServerTimestamps = mutableMapOf<Long, Long>()

    override suspend fun insertNote(note: NoteEntity): Long {
        val id = if (note.id == 0L) nextId++ else note.id
        notes[id] = note.copy(id = id)
        return id
    }

    override suspend fun updateNote(note: NoteEntity) {
        notes[note.id] = note
    }

    override suspend fun deleteNote(note: NoteEntity) {
        notes.remove(note.id)
    }

    override suspend fun getNoteById(id: Long): NoteWithLabels? {
        val note = notes[id] ?: return null
        return NoteWithLabels(
            note = note,
            labels = emptyList(), // Not fully implemented for fake
            checklist = checklistItems[id] ?: emptyList()
        )
    }

    override suspend fun getNoteEntityById(id: Long): NoteEntity? = notes[id]

    override suspend fun getLabelsForNote(noteId: Long): List<LabelEntity> = emptyList()

    override suspend fun getChecklistItemsForNote(noteId: Long): List<ChecklistItemEntity> = checklistItems[noteId] ?: emptyList()

    override suspend fun getAllNotesForBackup(): List<NoteWithLabels> {
        return notes.values.map { note ->
            NoteWithLabels(note, emptyList(), checklistItems[note.id] ?: emptyList())
        }
    }

    override suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long) {
        updatedServerTimestamps[noteId] = serverUpdatedAt
        notes[noteId] = notes[noteId]?.copy(serverUpdatedAt = serverUpdatedAt) ?: return
    }

    override suspend fun getCloudEligibleNoteCount(): Int = notes.size

    override suspend fun insertChecklistItem(item: ChecklistItemEntity) {
        val list = checklistItems.getOrPut(item.noteId) { mutableListOf() }
        list.add(item)
    }

    override suspend fun deleteChecklistItems(noteId: Long) {
        checklistItems.remove(noteId)
    }

    override suspend fun insertNoteLabelCrossRef(crossRef: NoteLabelCrossRef) {
        crossRefs.add(crossRef)
    }

    override suspend fun deleteNoteLabelCrossRefs(noteId: Long) {
        crossRefs.removeAll { it.noteId == noteId }
    }

    override suspend fun getWidgetNotes(): List<NoteWithLabels> = emptyList()

    // --- Unused ---
    override fun getActiveNotes(): Flow<List<NoteWithLabels>> = MutableStateFlow(emptyList<NoteWithLabels>())
    override fun getArchivedNotes(): Flow<List<NoteWithLabels>> = MutableStateFlow(emptyList())
    override fun getTrashedNotes(): Flow<List<NoteWithLabels>> = MutableStateFlow(emptyList())
    override suspend fun updateNotePosition(id: Long, position: Int, timestamp: Long) {}
    override suspend fun deleteAllNotes() { notes.clear() }
    override suspend fun deleteAllChecklistItems() { checklistItems.clear() }
    override suspend fun deleteAllNoteLabelCrossRefs() { crossRefs.clear() }
    override suspend fun getNextNotePosition(): Int = 0
    override fun getActiveNoteCount(): Flow<Int> = MutableStateFlow(notes.size)
    override suspend fun getNotesWithActiveReminders(now: Long): List<NoteWithLabels> = emptyList()
    override suspend fun getNotesWithMissedReminders(now: Long): List<NoteWithLabels> = emptyList()
    override suspend fun clearReminderTimestamp(noteId: Long) {}
}

class FakeLabelDao : LabelDao {
    val labels = mutableMapOf<Long, LabelEntity>()
    var nextId = 1L

    override suspend fun insertLabel(label: LabelEntity): Long {
        val id = if (label.id == 0L) nextId++ else label.id
        labels[id] = label.copy(id = id)
        return id
    }

    override suspend fun getAllLabelsOnce(): List<LabelEntity> = labels.values.toList()

    override suspend fun getLabelById(id: Long): LabelEntity? = labels[id]

    // --- Unused ---
    override fun getAllLabels(): Flow<List<LabelEntity>> = MutableStateFlow(emptyList())
    override suspend fun updateLabel(label: LabelEntity) {}
    override suspend fun deleteLabel(label: LabelEntity) {}
    override suspend fun deleteCrossRefsForLabel(labelId: Long) {}
    override suspend fun deleteAllLabels() { labels.clear() }
}
