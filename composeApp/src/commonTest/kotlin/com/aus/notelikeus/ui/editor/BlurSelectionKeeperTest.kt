package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compose collapses the body's selection as it loses focus; the toolbar has to format what the
 * user selected, not an empty caret. Both orders Compose may deliver the two events in are covered.
 */
class BlurSelectionKeeperTest {

    private val selected = TextFieldValue("alpha beta", TextRange(6, 10))
    private val collapsed = selected.copy(selection = TextRange(10))

    @Test
    fun `a collapse arriving after the focus loss is refused`() {
        val keeper = BlurSelectionKeeper()
        keeper.onFocusChanged(true, selected)
        assertNull(keeper.onFocusChanged(false, selected))
        assertFalse(keeper.accept(selected, collapsed))
    }

    @Test
    fun `a collapse arriving before the focus loss is undone by it`() {
        val keeper = BlurSelectionKeeper()
        keeper.onFocusChanged(true, selected)
        assertTrue(keeper.accept(selected, collapsed))
        assertEquals(selected, keeper.onFocusChanged(false, collapsed))
    }

    @Test
    fun `the undo does not depend on the caller having seen the collapse yet`() {
        // On Android the screen's state still held the selection when the focus loss arrived.
        val keeper = BlurSelectionKeeper()
        keeper.onFocusChanged(true, selected)
        assertTrue(keeper.accept(selected, collapsed))
        assertEquals(selected, keeper.onFocusChanged(false, selected))
    }

    @Test
    fun `a selection made leftwards is kept too`() {
        val leftwards = TextFieldValue("alpha beta", TextRange(10, 6))
        val keeper = BlurSelectionKeeper()
        keeper.onFocusChanged(true, leftwards)
        assertTrue(keeper.accept(leftwards, leftwards.copy(selection = TextRange(10))))
        assertEquals(leftwards, keeper.onFocusChanged(false, leftwards))
    }

    @Test
    fun `collapsing the selection while focused is the user's own and stays`() {
        val keeper = BlurSelectionKeeper()
        keeper.onFocusChanged(true, selected)
        assertTrue(keeper.accept(selected, collapsed)) // e.g. the Right arrow key
        val typed = TextFieldValue("alpha beta!", TextRange(11))
        assertTrue(keeper.accept(collapsed, typed))
        // A later focus loss has nothing to restore: the last change was typing, not a collapse.
        assertNull(keeper.onFocusChanged(false, typed))
    }

    @Test
    fun `text changes are never refused, focused or not`() {
        val keeper = BlurSelectionKeeper()
        val edited = TextFieldValue("alpha", TextRange(5))
        assertTrue(keeper.accept(selected, edited))
        keeper.onFocusChanged(true, selected)
        assertTrue(keeper.accept(selected, edited))
    }
}
