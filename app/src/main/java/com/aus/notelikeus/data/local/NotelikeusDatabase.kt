package com.aus.notelikeus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.AttachmentEntity
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef

@Database(
    entities = [
        NoteEntity::class,
        LabelEntity::class,
        NoteLabelCrossRef::class,
        AttachmentEntity::class,
        ChecklistItemEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class NotelikeusDatabase : RoomDatabase() {
    abstract val noteDao: NoteDao
    abstract val labelDao: LabelDao

    companion object {
        const val DATABASE_NAME = "notelikeus_db"
    }
}
