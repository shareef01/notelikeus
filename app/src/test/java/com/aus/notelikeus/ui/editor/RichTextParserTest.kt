package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextParserTest {

    @Test
    fun parse_appliesBoldStyle() {
        val result = RichTextParser.parse(
            text = "**hello** world",
            contentColor = Color.Black
        )

        assertEquals("hello world", result.text)
        assertEquals(FontWeight.Bold, result.spanStyles.first().item.fontWeight)
        assertEquals("hello", result.text.substring(
            result.spanStyles.first().start,
            result.spanStyles.first().end
        ))
    }

    @Test
    fun parse_appliesItalicStyle() {
        val result = RichTextParser.parse(
            text = "_emphasis_",
            contentColor = Color.Black
        )

        assertEquals("emphasis", result.text)
        assertEquals(FontStyle.Italic, result.spanStyles.first().item.fontStyle)
    }

    @Test
    fun parse_preservesBulletPrefix() {
        val result = RichTextParser.parse(
            text = "• item one",
            contentColor = Color.Black
        )

        assertTrue(result.text.startsWith("• "))
    }
}
