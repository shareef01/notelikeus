package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The formatter sits directly in the editor's `onValueChange`, so anything it throws takes the
 * user's keystroke with it: the state update never runs, the field reverts to its previous text,
 * and the autosave behind it then persists that older text. Losing typed content here is
 * indistinguishable from the app deleting it.
 */
class SmartTextProcessorTest {

    private fun process(
        previousText: String,
        previousCaret: Int,
        newText: String,
        newCaret: Int
    ) = SmartTextProcessor.process(
        TextFieldValue(newText, TextRange(newCaret)),
        TextFieldValue(previousText, TextRange(previousCaret))
    )

    @Test
    fun `a paste landing behind the old caret does not throw or lose text`() {
        // The caret was at the end; the user clicked to the front and pasted. Text grows while the
        // caret moves backwards, which is what the old start..start slice could not express.
        val result = process("hello world", 11, "Xhello world", 1)
        assertEquals("Xhello world", result.value.text)
        assertFalse(result.structureChanged)
    }

    @Test
    fun `replacing a selection with a longer paste keeps every character`() {
        val result = process("hello world", 11, "hello everybody", 15)
        assertEquals("hello everybody", result.value.text)
    }

    @Test
    fun `a multi-character paste is never reinterpreted as formatting`() {
        // Pasting "- " should stay "- ", not silently become a bullet.
        val result = process("", 0, "- ", 2)
        assertEquals("- ", result.value.text)
    }

    @Test
    fun `an active selection is passed through untouched`() {
        val result = SmartTextProcessor.process(
            TextFieldValue("hello world", TextRange(2, 7)),
            TextFieldValue("hello worl", TextRange(10))
        )
        assertEquals("hello world", result.value.text)
    }

    // ---- the formatting behaviour itself still works ----

    @Test
    fun `typing space after a dash makes a bullet and keeps the rest of the line`() {
        val result = process("-TAIL", 1, "- TAIL", 2)
        assertEquals("• TAIL", result.value.text)
    }

    @Test
    fun `enter after a bullet continues the list`() {
        val result = process("• milk", 6, "• milk\n", 7)
        assertEquals("• milk\n• ", result.value.text)
    }

    @Test
    fun `enter on an empty bullet exits the list`() {
        val result = process("• milk\n• ", 9, "• milk\n• \n", 10)
        assertEquals("• milk\n\n", result.value.text)
    }

    @Test
    fun `enter after a numbered item continues the numbering`() {
        val result = process("1. milk", 7, "1. milk\n", 8)
        assertEquals("1. milk\n2. ", result.value.text)
    }

    @Test
    fun `typing the checklist marker signals a structure change`() {
        val result = process("[]", 2, "[] ", 3)
        assertTrue(result.structureChanged)
    }
}
