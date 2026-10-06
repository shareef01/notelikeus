package com.aus.notelikeus.ui.editor

import com.aus.notelikeus.domain.model.Note

/**
 * Outcome of moving the editor's note to the trash.
 *
 * [AccountChanged] is deliberately a separate case rather than one more meaning for a null
 * snapshot: "there was nothing to trash" and "the trash was refused because the account-owned
 * dataset changed" are different facts, and only one of them justifies offering an undo.
 */
sealed interface TrashNoteResult {
    /** The note was trashed locally. [snapshot] is the pre-trash note, for an undo. */
    data class Trashed(val snapshot: Note) : TrashNoteResult

    /** There was no content to trash. */
    data object NothingToTrash : TrashNoteResult

    /**
     * The trash was intentionally refused because the local account-owned dataset changed after the
     * action began. Nothing was removed, so this is neither a deletion nor a failure, and it carries
     * no [Throwable] because nothing threw.
     */
    data object AccountChanged : TrashNoteResult
}
