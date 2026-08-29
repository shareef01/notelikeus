package com.aus.notelikeus.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteViewModeTest {

    @Test
    fun `next cycles the three web view options`() {
        assertEquals(NoteViewMode.GRID_2, NoteViewMode.LIST.next())
        assertEquals(NoteViewMode.COMPACT, NoteViewMode.GRID_2.next())
        assertEquals(NoteViewMode.LIST, NoteViewMode.COMPACT.next())
    }

    @Test
    fun `a leftover multi-column grid steps to compact rather than the next column count`() {
        assertEquals(NoteViewMode.COMPACT, NoteViewMode.GRID_3.next())
        assertEquals(NoteViewMode.COMPACT, NoteViewMode.GRID_5.next())
    }
}
