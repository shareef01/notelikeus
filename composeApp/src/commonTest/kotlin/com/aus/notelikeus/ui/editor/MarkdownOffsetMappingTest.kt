package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownOffsetMappingTest {

    @Test
    fun `bold markers are hidden in transformed text`() {
        val transformed = RichTextParser.toTransformedText("**hello**", contentColor = Color.Black)
        assertEquals("hello", transformed.text.text)
    }

    @Test
    fun `offset mapping keeps cursor inside bold content`() {
        val source = "**hello**"
        val mapping = RichTextParser.buildOffsetMapping(source)

        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.originalToTransformed(2))
        assertEquals(2, mapping.originalToTransformed(4))
        assertEquals(5, mapping.originalToTransformed(7))
        assertEquals(5, mapping.originalToTransformed(8))
    }

    @Test
    fun `offset mapping round trips for italic text`() {
        val source = "_hi_"
        val mapping = RichTextParser.buildOffsetMapping(source)

        assertEquals(1, mapping.originalToTransformed(2))
        assertEquals(2, mapping.transformedToOriginal(1))
    }

    @Test
    fun `underscore bold markers are hidden too`() {
        val transformed = RichTextParser.toTransformedText("__hello__", contentColor = Color.Black)
        assertEquals("hello", transformed.text.text)
    }

    /**
     * The half that can crash rather than merely look wrong.
     *
     * The transformation feeds a real text field, so a mapping that disagrees with the transformed
     * text throws out of Compose's own text layout. `__` has the same two-character geometry as
     * `**`, and this asserts it rather than assuming it.
     */
    @Test
    fun `offset mapping keeps cursor inside underscore bold content`() {
        val source = "__hello__"
        val mapping = RichTextParser.buildOffsetMapping(source)

        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.originalToTransformed(2))
        assertEquals(2, mapping.originalToTransformed(4))
        assertEquals(5, mapping.originalToTransformed(7))
        assertEquals(5, mapping.originalToTransformed(8))
    }

    /**
     * Every offset in the source has to map into the transformed text, for both spellings and for
     * text that mixes them. Anything outside that range is an exception in a text field, not a
     * cosmetic slip.
     */
    @Test
    fun `every offset maps inside the transformed text`() {
        val sources = listOf(
            "__hello__",
            "**hello**",
            "_hi_",
            "__one__ and **two** and _three_",
            "__unclosed emphasis",
            "plain text with no markers",
            "__a__b__c__"
        )

        for (source in sources) {
            val transformed = RichTextParser.toTransformedText(source, contentColor = Color.Black)
            val mapping = RichTextParser.buildOffsetMapping(source)
            val length = transformed.text.text.length

            for (offset in 0..source.length) {
                val mapped = mapping.originalToTransformed(offset)
                assertEquals(
                    true,
                    mapped in 0..length,
                    "originalToTransformed($offset) = $mapped out of 0..$length for \"$source\""
                )
            }
            for (offset in 0..length) {
                val mapped = mapping.transformedToOriginal(offset)
                assertEquals(
                    true,
                    mapped in 0..source.length,
                    "transformedToOriginal($offset) = $mapped out of 0..${source.length} for \"$source\""
                )
            }
        }
    }

    @Test
    fun `markdown link markers are hidden`() {
        val transformed = RichTextParser.toTransformedText(
            "[docs](https://example.com)",
            contentColor = Color.Black
        )
        assertEquals("docs", transformed.text.text)
    }
}
