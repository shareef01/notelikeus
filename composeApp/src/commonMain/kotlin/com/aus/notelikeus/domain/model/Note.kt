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
    val reminderTimestamp: Long? = null,
    /**
     * Firestore's server-assigned commit time (epoch millis) as of the last time this device
     * observed a write to this note in the cloud — either its own upload or a download. Null
     * until the note has synced at least once under this scheme. Used instead of [timestamp]
     * (a client clock, spoofable and skew-prone across devices) to decide which copy wins a
     * sync conflict; see FirebaseNoteSync.kt.
     */
    val serverUpdatedAt: Long? = null,
    val labels: List<Label> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList()
)
