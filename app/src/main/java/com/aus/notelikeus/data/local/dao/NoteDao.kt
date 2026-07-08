package com.aus.notelikeus.data.local.dao

import androidx.room.*
import com.aus.notelikeus.data.local.entity.AttachmentEntity
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.local.model.NoteWithLabelsAndAttachments
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Transaction
    @Query("SELECT * FROM notes WHERE isTrashed = 0 AND isArchived = 0 ORDER BY isPinned DESC, position ASC, timestamp DESC")
    fun getActiveNotes(): Flow<List<NoteWithLabelsAndAttachments>>

    @Transaction
    @Query("SELECT * FROM notes WHERE isArchived = 1 AND isTrashed = 0 ORDER BY timestamp DESC")
    fun getArchivedNotes(): Flow<List<NoteWithLabelsAndAttachments>>

    @Transaction
    @Query("SELECT * FROM notes WHERE isTrashed = 1 ORDER BY timestamp DESC")
    fun getTrashedNotes(): Flow<List<NoteWithLabelsAndAttachments>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NoteWithLabelsAndAttachments?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteLabelCrossRef(crossRef: NoteLabelCrossRef)

    @Query("DELETE FROM note_label_cross_ref WHERE noteId = :noteId")
    suspend fun deleteNoteLabelCrossRefs(noteId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItemEntity)

    @Query("DELETE FROM checklist_items WHERE noteId = :noteId")
    suspend fun deleteChecklistItems(noteId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE noteId = :noteId")
    suspend fun deleteAttachments(noteId: Long)

    @Transaction
    @Query("SELECT * FROM notes WHERE isTrashed = 0 AND isArchived = 0 ORDER BY isPinned DESC, timestamp DESC LIMIT 5")
    suspend fun getWidgetNotes(): List<NoteWithLabelsAndAttachments>
}
