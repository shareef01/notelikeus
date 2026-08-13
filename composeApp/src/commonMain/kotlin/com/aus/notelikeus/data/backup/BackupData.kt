package com.aus.notelikeus.data.backup

import com.aus.notelikeus.domain.model.Label
import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: Int,
    val exportedAt: Long = 0L,
    val app: String = "Notelikeus",
    val appVersion: String = "1.0.0",
    val labels: List<Label> = emptyList(),
    val notes: List<NoteBackupDto>
)

@Serializable
data class NoteBackupDto(
    val id: Long? = null,
    val title: String,
    val content: String,
    val timestamp: Long,
    val color: Int,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val position: Int = 0,
    val reminderTimestamp: Long? = null,
    val labels: List<String> = emptyList(),
    val checklist: List<ChecklistItemBackupDto> = emptyList()
)

@Serializable
data class ChecklistItemBackupDto(
    val text: String,
    val isChecked: Boolean,
    val position: Int
)
