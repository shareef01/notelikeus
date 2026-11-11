package com.aus.notelikeus.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NL-11: moving notes to Trash is undoable, so the selection bar and the Delete key do it at once
 * (with Undo) instead of asking. Only deleting from Trash, which is permanent, still asks.
 */
class SelectionDeleteTest {

    @Test
    fun `outside Trash the selection is moved to Trash without a dialog`() {
        NoteFilter.entries.filter { it != NoteFilter.TRASHED }.forEach { filter ->
            assertEquals(SelectionDelete.MOVE_TO_TRASH, selectionDeleteFor(filter), "in $filter")
        }
    }

    @Test
    fun `in Trash a permanent delete still asks first`() {
        assertEquals(SelectionDelete.CONFIRM_PERMANENT_DELETE, selectionDeleteFor(NoteFilter.TRASHED))
    }
}
