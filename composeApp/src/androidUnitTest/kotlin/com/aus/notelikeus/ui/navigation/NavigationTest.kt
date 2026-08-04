package com.aus.notelikeus.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {

    @Test
    fun `editor route includes note id`() {
        assertEquals(
            "editor/42?initialColor=${Int.MIN_VALUE}",
            Screen.Editor.createRoute(42L)
        )
    }

    @Test
    fun `editor route uses sentinel for new note`() {
        assertEquals(
            "editor/-1?initialColor=${Int.MIN_VALUE}",
            Screen.Editor.createRoute(null)
        )
    }
}
