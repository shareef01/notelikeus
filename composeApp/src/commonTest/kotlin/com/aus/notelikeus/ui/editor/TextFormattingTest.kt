package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Formatting has to do something when nothing is selected.
 *
 * Tapping Bold with no selection is not an edge case — it is what you do when you are *about* to
 * type something bold. All three of these used to return the text untouched, so B, I and the link
 * button were dead controls in the ordinary case, with no selection on screen to hint at why.
 */
class TextFormattingTest {

    @Test
    fun wrapSelection_withNoSelection_opensMarkersAtTheCursor() {
        val input = TextFieldValue("hello world", TextRange(5))
        val result = TextFormatting.wrapSelection(input, "**")

        assertEquals("hello**** world", result.text)
        // Between the pair, so the next thing typed lands inside it.
        assertEquals(TextRange(7), result.selection)
    }

    @Test
    fun wrapSelection_withNoSelection_worksAtBothEnds() {
        assertEquals("__hi", TextFormatting.wrapSelection(TextFieldValue("hi", TextRange(0)), "_").text)
        assertEquals("hi__", TextFormatting.wrapSelection(TextFieldValue("hi", TextRange(2)), "_").text)
    }

    @Test
    fun wrapSelection_withNoSelection_onEmptyTextStillOpensMarkers() {
        val result = TextFormatting.wrapSelection(TextFieldValue("", TextRange(0)), "**")

        assertEquals("****", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    /** The worst of the three: the dialog took a URL, then threw it away. */
    @Test
    fun wrapAsLink_withNoSelection_insertsALinkLabelledWithTheUrl() {
        val input = TextFieldValue("see ", TextRange(4))
        val result = TextFormatting.wrapAsLink(input, "example.com")

        assertEquals("see [example.com](https://example.com)", result.text)
        assertEquals(TextRange(result.text.length), result.selection)
    }

    @Test
    fun wrapAsLink_withNoSelection_keepsAnExplicitScheme() {
        val result = TextFormatting.wrapAsLink(TextFieldValue("", TextRange(0)), "http://example.com")

        assertEquals("[http://example.com](http://example.com)", result.text)
    }

    /** A blank URL is not a link, so nothing is inserted rather than an empty one. */
    @Test
    fun wrapAsLink_withABlankUrl_changesNothing() {
        val selected = TextFieldValue("hello", TextRange(2, 4))
        val collapsed = TextFieldValue("hello", TextRange(2))

        assertEquals(selected, TextFormatting.wrapAsLink(selected, "   "))
        assertEquals(collapsed, TextFormatting.wrapAsLink(collapsed, ""))
    }

    /** Bullets already worked without a selection: they act on the line the cursor is in. */
    @Test
    fun prefixLinesWithBullet_withNoSelection_bulletsTheCurrentLine() {
        val input = TextFieldValue("line one\nline two", TextRange(12))
        val result = TextFormatting.prefixLinesWithBullet(input)

        assertEquals("line one\n• line two", result.text)
    }

    @Test
    fun wrapSelection_appliesBoldMarkers() {
        val input = TextFieldValue("hello world", TextRange(0, 5))
        val result = TextFormatting.wrapSelection(input, "**")

        assertEquals("**hello** world", result.text)
        assertEquals(TextRange(2, 7), result.selection)
    }

    @Test
    fun prefixLinesWithBullet_addsBulletPrefix() {
        val input = TextFieldValue("line one\nline two", TextRange(0, 17))
        val result = TextFormatting.prefixLinesWithBullet(input)

        assertEquals("• line one\n• line two", result.text)
    }

    @Test
    fun prefixLinesWithBullet_onlyAffectsSelectedBlock() {
        val input = TextFieldValue("line one\nline two", TextRange(0, 4))
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
