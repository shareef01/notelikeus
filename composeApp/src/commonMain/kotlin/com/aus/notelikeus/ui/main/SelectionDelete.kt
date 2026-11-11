package com.aus.notelikeus.ui.main

/**
 * What the selection bar's delete button (and the Delete key) does in a given view.
 *
 * The rule, shared with web: a reversible action happens at once and offers Undo; only an
 * irreversible one asks first. Moving notes to Trash can be undone and the notes can be restored
 * from Trash later, so it never asks. Deleting from Trash is permanent, so it does.
 */
internal enum class SelectionDelete {
    /** Move the selection to Trash now and offer Undo. No dialog. */
    MOVE_TO_TRASH,

    /** Ask before deleting the selection for good. */
    CONFIRM_PERMANENT_DELETE,
}

internal fun selectionDeleteFor(filter: NoteFilter): SelectionDelete =
    if (filter == NoteFilter.TRASHED) {
        SelectionDelete.CONFIRM_PERMANENT_DELETE
    } else {
        SelectionDelete.MOVE_TO_TRASH
    }
