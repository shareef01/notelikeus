package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichTextParserTest {

    /**
     * Found on a real note, on a real device.
     *
     * `__bold__` is the other CommonMark spelling, and the app's own toolbar never writes it -- so
     * it arrives by paste or by hand. The single-underscore italic rule used to eat the *outer*
     * pair and leave the inner two on screen, which is how a note came to read
     * `_this is a new note_` with the underscores showing.
     */
    @Test
    fun parse_appliesBoldStyleToUnderscoreSpelling() {
        val result = RichTextParser.parse(
            text = "__hello__ world",
            contentColor = Color.Black
        )

        assertEquals("hello world", result.text)
        assertEquals(FontWeight.Bold, result.spanStyles.first().item.fontWeight)
    }

    @Test
    fun parse_doesNotLeaveStrayUnderscores() {
        val result = RichTextParser.parse(
            text = "__this is a new note__",
            contentColor = Color.Black
        )

        assertEquals("this is a new note", result.text)
    }

    /** The single-underscore rule still has to work, and must not swallow a `__` pair. */
    @Test
    fun parse_keepsItalicAndBoldSpellingsApart() {
        val italic = RichTextParser.parse(text = "_hello_", contentColor = Color.Black)
        assertEquals("hello", italic.text)
        assertEquals(FontStyle.Italic, italic.spanStyles.first().item.fontStyle)

        val bold = RichTextParser.parse(text = "__hello__", contentColor = Color.Black)
        assertEquals("hello", bold.text)
        assertEquals(FontWeight.Bold, bold.spanStyles.first().item.fontWeight)
    }

    @Test
    fun parse_handlesBothSpellingsInOneString() {
        val result = RichTextParser.parse(
            text = "__one__ and **two** and _three_",
            contentColor = Color.Black
        )

        assertEquals("one and two and three", result.text)
    }

    /** An unclosed marker is not emphasis, and markdown leaves it alone rather than guessing. */
    @Test
    fun parse_leavesAnUnclosedMarkerAsText() {
        val result = RichTextParser.parse(
            text = "__unclosed emphasis",
            contentColor = Color.Black
        )

        assertEquals("__unclosed emphasis", result.text)
    }

    /**
     * There is no base colour layer under the spans, so every character has to be covered by one
     * or it renders uncoloured. Also what keeps `spanStyles.first()` the emphasis a caller is
     * looking for, rather than a full-width colour span sitting in front of it.
     */
    @Test
    fun `every character of the output belongs to a span`() {
        val sources = listOf(
            "plain text",
            "**bold** and _italic_",
            "**a __b__ c**",
            "see [docs](https://example.com) now",
            "bare https://example.com here",
            "trailing plain after **bold**"
        )

        for (source in sources) {
            val result = RichTextParser.parse(text = source, contentColor = Color.Black)
            val covered = BooleanArray(result.text.length)
            result.spanStyles.forEach { range ->
                for (i in range.start until range.end) covered[i] = true
            }
            val gaps = covered.indices.filter { !covered[it] }
            assertTrue(gaps.isEmpty(), "uncovered offsets $gaps in \"$source\" -> \"${result.text}\"")
        }
    }

    /** Nested emphasis carries both styles, not just the outer one. */
    @Test
    fun parse_mergesNestedEmphasis() {
        val result = RichTextParser.parse(text = "**_both_**", contentColor = Color.Black)

        assertEquals("both", result.text)
        val style = result.spanStyles.first { it.start == 0 }.item
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(FontStyle.Italic, style.fontStyle)
    }

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

    @Test
    fun parse_rendersMarkdownLinkLabel() {
        val result = RichTextParser.parse(
            text = "Visit [docs](https://example.com) now",
            contentColor = Color.Black,
            linkColor = Color.Blue
        )

        assertEquals("Visit docs now", result.text)
        val links = result.getLinkAnnotations(0, result.text.length)
        assertTrue(links.any { it.item is LinkAnnotation.Url })
    }

    @Test
    fun parse_autoLinksBareUrls() {
        val result = RichTextParser.parse(
            text = "See https://example.com today",
            contentColor = Color.Black,
            linkColor = Color.Blue
        )

        assertEquals("See https://example.com today", result.text)
        assertEquals(1, result.getLinkAnnotations(0, result.text.length).size)
    }

    @Test
    fun parse_doesNotMakeJavascriptUrlsClickable() {
        val result = RichTextParser.parse(
            text = "Visit [docs](javascript:alert) now",
            contentColor = Color.Black,
            linkColor = Color.Blue
        )

        assertEquals("Visit docs now", result.text)
        assertTrue(result.getLinkAnnotations(0, result.text.length).isEmpty())
    }
}
