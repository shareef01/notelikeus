package com.aus.notelikeus.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "note_label_cross_ref",
    primaryKeys = ["noteId", "labelId"],
    indices = [Index(value = ["labelId"])],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LabelEntity::class,
            parentColumns = ["id"],
            childColumns = ["labelId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class NoteLabelCrossRef(
    val noteId: Long,
    val labelId: Long
)
