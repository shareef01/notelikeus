package com.aus.notelikeus.data.local.model

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef

data class NoteWithLabels(
    @Embedded val note: NoteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = NoteLabelCrossRef::class,
            parentColumn = "noteId",
            entityColumn = "labelId"
        )
    )
    val labels: List<LabelEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "noteId"
    )
    val checklist: List<ChecklistItemEntity>
)
