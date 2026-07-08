package com.aus.notelikeus.domain.model

data class Note(
    val id: Long? = null,
    val title: String,
    val content: String,
    val timestamp: Long,
    val color: Int,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val position: Int = 0,
    val isLocked: Boolean = false,
    val reminderTimestamp: Long? = null,
    val labels: List<Label> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList()
)
