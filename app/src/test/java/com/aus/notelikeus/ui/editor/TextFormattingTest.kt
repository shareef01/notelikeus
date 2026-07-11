package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormattingTest {

    @Test
    fun wrapSelection_appliesBoldMarkers() {
        val input = TextFieldValue("hello world", TextRange(0, 5))
        val result = TextFormatting.wrapSelection(input, "**")

        assertEquals("**hello** world", result.text)
        assertEquals(TextRange(2, 7), result.selection)
    }

    @Test
    fun prefixLinesWithBullet_addsBulletPrefix() {
        val input = TextFieldValue("line one\nline two", TextRange(0, 7))
        val result = TextFormatting.prefixLinesWithBullet(input)

        assertEquals("• line one\nline two", result.text)
    }

    @Test
    fun wrapAsLink_wrapsSelectionWithMarkdownLink() {
        val input = TextFieldValue("tap here", TextRange(4, 8))
        val result = TextFormatting.wrapAsLink(input, "example.com")

        assertEquals("tap [here](https://example.com)", result.text)
        assertEquals(TextRange(31, 31), result.selection)
    }
}
