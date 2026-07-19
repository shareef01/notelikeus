package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable

/**
 * Marked @Immutable so Compose can skip recomposing NoteCard/SwipeableNoteCard when an
 * unrelated note changes — without it, the plain List<> fields make this class
 * Compose-unstable by default, forcing every visible card to recompose on any single
 * note mutation. Safe because every field is truly immutable: val-only, never mutated
 * in place, always replaced via .copy().
 */
@Immutable
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
