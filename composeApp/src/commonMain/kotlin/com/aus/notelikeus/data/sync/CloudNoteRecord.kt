package com.aus.notelikeus.data.sync

/**
 * A note document as it appears in cloud storage, carrying the two timestamps
 * the conflict tie-break needs plus the flattened note fields.
 *
 * The engine resolves label names to [com.aus.notelikeus.domain.model.Label]
 * entities itself via [com.aus.notelikeus.domain.repository.NoteRepository];
 * the transport does not know about labels.
 */
data class CloudNoteRecord(
    val noteId: Long,

    /** Server-assigned commit time, or null if not yet round-tripped. */
    val serverUpdatedAt: Long?,

    /** Client-side epoch-millis; null when absent or predates the field. */
    val clientTimestamp: Long?,

    // ---- flattened Note fields ----

    val title: String,
    val content: String,
    val timestamp: Long,
    val color: Int,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val position: Int,
    val reminderTimestamp: Long?,
    val labels: List<String>,
    val checklistItems: List<ChecklistItemData>
)

/**
 * A checklist item as seen in cloud storage (no local [id] — the engine
 * reassigns ids during insert).
 */
data class ChecklistItemData(
    val text: String,
    val isChecked: Boolean,
    val position: Int
)
