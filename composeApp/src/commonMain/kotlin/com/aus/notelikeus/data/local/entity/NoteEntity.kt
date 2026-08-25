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
    /**
     * Vestigial. Note locking was removed, but the column stays so the schema is unchanged
     * and no migration is needed. Recreating `notes` to drop it would fire the ON DELETE
     * CASCADE that `checklist_items` and `note_label_cross_ref` declare against it, and
     * SQLite's DROP TABLE performs an implicit DELETE FROM — not worth risking every
     * user's checklists and label links to reclaim one boolean.
     */
    val isLocked: Boolean = false,
    val reminderTimestamp: Long?,
    /** See [com.aus.notelikeus.domain.model.Note.serverUpdatedAt]. */
    val serverUpdatedAt: Long? = null,
    /**
     * The note's searchable text, folded once on write. See `buildSearchText`.
     *
     * **Null means "not yet indexed", not "no text"**, and the two must never be confused: a null
     * here makes the matcher fall back to folding the note's fields on the spot, so a note is
     * always findable. If this defaulted to an empty string instead, every row the backfill had
     * not reached yet would silently match nothing — notes vanishing from search is exactly the
     * failure this column exists to avoid.
     *
     * Local only. It is derived data, so it is not in the backup format and not in the cloud
     * document; each client folds for itself.
     */
    val searchText: String? = null
)
