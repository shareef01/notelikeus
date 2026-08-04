package com.aus.notelikeus.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)
