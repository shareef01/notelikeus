package com.aus.notelikeus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef

/**
 * The Room schema version, named so the diagnostics report can state it without a second literal
 * drifting away from the one Room actually uses.
 */
const val NOTELIKEUS_DATABASE_VERSION = 11

@Database(
    entities = [
        NoteEntity::class,
        LabelEntity::class,
        NoteLabelCrossRef::class,
        ChecklistItemEntity::class
    ],
    version = NOTELIKEUS_DATABASE_VERSION,
    exportSchema = true
)
abstract class NotelikeusDatabase : RoomDatabase() {
    abstract val noteDao: NoteDao
    abstract val labelDao: LabelDao

    companion object {
        const val DATABASE_NAME = "notelikeus_db"
    }
}
