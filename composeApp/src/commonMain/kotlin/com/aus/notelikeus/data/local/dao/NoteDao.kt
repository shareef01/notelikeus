package com.aus.notelikeus.data.local.dao

import androidx.room.*
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.local.model.NoteWithLabels
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Transaction
    @Query("SELECT * FROM notes WHERE isTrashed = 0 AND isArchived = 0 ORDER BY isPinned DESC, position ASC, timestamp DESC")
    fun getActiveNotes(): Flow<List<NoteWithLabels>>

    @Transaction
    @Query("SELECT * FROM notes WHERE isArchived = 1 AND isTrashed = 0 ORDER BY timestamp DESC")
    fun getArchivedNotes(): Flow<List<NoteWithLabels>>

    @Transaction
    @Query("SELECT * FROM notes WHERE isTrashed = 1 ORDER BY timestamp DESC")
    fun getTrashedNotes(): Flow<List<NoteWithLabels>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NoteWithLabels?

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteEntityById(id: Long): NoteEntity?

    @Query("SELECT labels.* FROM labels INNER JOIN note_label_cross_ref ON labels.id = note_label_cross_ref.labelId WHERE note_label_cross_ref.noteId = :noteId")
    suspend fun getLabelsForNote(noteId: Long): List<LabelEntity>

    @Query("SELECT * FROM checklist_items WHERE noteId = :noteId ORDER BY position ASC")
    suspend fun getChecklistItemsForNote(noteId: Long): List<ChecklistItemEntity>

    /** Notes carrying [labelId] — the set whose cloud copies go stale when a label is renamed. */
    @Query("SELECT noteId FROM note_label_cross_ref WHERE labelId = :labelId")
    suspend fun getNoteIdsForLabel(labelId: Long): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM notes WHERE isTrashed = 0 AND isArchived = 0")
    suspend fun getNextNotePosition(): Int

    @Query("SELECT COUNT(*) FROM notes WHERE isTrashed = 0 AND isArchived = 0")
    fun getActiveNoteCount(): Flow<Int>

    @Transaction
    @Query(
        """
        SELECT * FROM notes
        WHERE reminderTimestamp IS NOT NULL
        AND reminderTimestamp > :now
        AND isTrashed = 0
        AND isArchived = 0
        """
    )
    suspend fun getNotesWithActiveReminders(now: Long): List<NoteWithLabels>

    @Transaction
    @Query(
        """
        SELECT * FROM notes
        WHERE reminderTimestamp IS NOT NULL
        AND reminderTimestamp <= :now
        AND isTrashed = 0
        AND isArchived = 0
        """
    )
    suspend fun getNotesWithMissedReminders(now: Long): List<NoteWithLabels>

    @Query("UPDATE notes SET reminderTimestamp = NULL WHERE id = :noteId")
    suspend fun clearReminderTimestamp(noteId: Long)

    @Query("UPDATE notes SET serverUpdatedAt = :serverUpdatedAt WHERE id = :noteId")
    suspend fun updateServerTimestamp(noteId: Long, serverUpdatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("UPDATE notes SET position = :position, timestamp = :timestamp WHERE id = :id")
    suspend fun updateNotePosition(id: Long, position: Int, timestamp: Long)

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

    @Query("DELETE FROM checklist_items")
    suspend fun deleteAllChecklistItems()

    @Query("DELETE FROM note_label_cross_ref")
    suspend fun deleteAllNoteLabelCrossRefs()

    @Query("DELETE FROM notes")
    suspend fun deleteAllNotes()

    @Transaction
    @Query("SELECT * FROM notes WHERE isTrashed = 0 AND isArchived = 0 ORDER BY isPinned DESC, position ASC, timestamp DESC LIMIT 5")
    suspend fun getWidgetNotes(): List<NoteWithLabels>

    @Transaction
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    suspend fun getAllNotesForBackup(): List<NoteWithLabels>

    /** Every note is cloud-eligible now that locking is gone. */
    @Query("SELECT COUNT(*) FROM notes")
    suspend fun getCloudEligibleNoteCount(): Int
}
