package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Marked @Immutable so Compose can skip recomposing NoteCard/SwipeableNoteCard when an
 * unrelated note changes — without it, the plain List<> fields make this class
 * Compose-unstable by default, forcing every visible card to recompose on any single
 * note mutation. Safe because every field is truly immutable: val-only, never mutated
 * in place, always replaced via .copy().
 */
@Immutable
@Serializable
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
     * sync conflict; see NoteSyncEngine.kt.
     */
    val serverUpdatedAt: Long? = null,
    val labels: List<Label> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    /**
     * The folded search text for this note, when it has been indexed. See `buildSearchText`.
     *
     * `@Transient` because it is derived, local and worthless to anyone else: it must not appear
     * in the backup JSON, where it would roughly double the file for no information, and it has no
     * place in the cloud document either -- every client folds for itself, using its own version
     * of the folding table.
     *
     * Null means "not yet indexed", never "no text". The matcher folds on the spot when it sees
     * null, so a note is findable from the moment it exists rather than from the moment the
     * backfill reaches it.
     */
    @Transient
    val searchText: String? = null
)
