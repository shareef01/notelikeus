package com.aus.notelikeus.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownOffsetMappingTest {

    @Test
    fun `bold markers are hidden in transformed text`() {
        val transformed = RichTextParser.toTransformedText("**hello**", contentColor = androidx.compose.ui.graphics.Color.Black)

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
    fun `markdown link markers are hidden`() {
        val transformed = RichTextParser.toTransformedText(
            "[docs](https://example.com)",
            contentColor = androidx.compose.ui.graphics.Color.Black
        )

        assertEquals("docs", transformed.text.text)
    }
}
