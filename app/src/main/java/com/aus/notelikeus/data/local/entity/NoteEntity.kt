package com.aus.notelikeus.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val timestamp: Long,
    val color: Int,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val position: Int,
    val isLocked: Boolean,
    val reminderTimestamp: Long?
)
