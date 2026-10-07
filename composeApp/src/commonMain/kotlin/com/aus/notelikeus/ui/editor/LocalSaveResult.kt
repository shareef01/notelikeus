package com.aus.notelikeus.ui.editor

/**
 * Outcome of writing the editor's contents to the local database.
 *
 * Local durability and cloud synchronisation are separate concerns, and conflating them is how a
 * network failure ends up reported as a lost note. Only Room deciding the write landed produces
 * [Saved]; an attachment upload or note sync failing afterwards leaves the save [Saved] and is
 * surfaced as pending sync instead.
 */
sealed interface LocalSaveResult {
    /** Room committed the note. [noteId] is stable and safe to keep in editor state. */
    data class Saved(val noteId: Long) : LocalSaveResult

    /** Nothing needed writing — the editor holds no content. */
    data object Unchanged : LocalSaveResult

    /**
     * The save was intentionally refused because the local account-owned dataset changed after this
     * save was initiated. This is **not** a local write failure: Room rejected nothing, and the
     * edit does not belong to the dataset that is current now, so writing it would land the user's
     * text in the wrong account's note. Callers must not treat it as a successful persistence of
     * the edit, and it deliberately carries no [Throwable] because nothing threw.
     */
    data object AccountChanged : LocalSaveResult

    /** The local write failed. The editor still holds the only copy of the edit. */
    data class Failed(val cause: Throwable) : LocalSaveResult
}
