package com.aus.notelikeus.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/**
 * A user note.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AppFunctionNote(
    /** The unique identifier of the note. */
    val id: Long,
    /** The title of the note. */
    val title: String,
    /** The text content of the note. */
    val content: String,
    /** Whether the note is pinned. */
    val isPinned: Boolean,
    /** Whether the note is archived. */
    val isArchived: Boolean,
    /** Optional timestamp for a reminder. */
    val reminderTimestamp: Long?
)
