package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * F22: a matched span used to be emitted as opaque text and never looked at again, so
     * `**bold with __inner__ inside**` rendered bold with the underscores still on screen.
     */
    @Test
    fun `nested emphasis hides its inner markers`() {
        val cases = mapOf(
            "**__inner__**" to "inner",
            "**_inner_**" to "inner",
            "__**inner**__" to "inner",
            "_**inner**_" to "inner",
            "**a __b__ c**" to "a b c",
            "**- __this is a new note__**" to "- this is a new note"
        )

        for ((source, expected) in cases) {
            assertEquals(
                expected,
                RichTextParser.toTransformedText(source, contentColor = Color.Black).text.text,
                "for \"$source\""
            )
        }
    }

    /**
     * The invariant the rewrite exists to guarantee.
     *
     * `toTransformedText` hands both to a real text field, and Compose throws if they disagree.
     * Before, they came from two independent walks that could drift on untested input; now they
     * come from one. This asserts it across everything the renderer can meet, nesting included.
     */
    @Test
    fun `the mapping always agrees with the text it was built for`() {
        val sources = listOf(
            "", "plain", "**b**", "__b__", "_i_", "**_bi_**", "__**bi**__",
            "**a __b__ c**", "mixed **b** and _i_ and [x](https://e.com)",
            "unclosed **", "unclosed __", "unclosed _", "**", "__", "_", "***", "____",
            "[label](https://example.com)", "bare https://example.com here",
            "**[link](https://e.com)**", "_https://e.com_",
            "a".repeat(200), "*".repeat(60), "_".repeat(60),
            "**nested **deeper** still**"
        )

        for (source in sources) {
            val transformed = RichTextParser.toTransformedText(source, contentColor = Color.Black)
            val mapping = transformed.offsetMapping
            val length = transformed.text.text.length

            for (offset in 0..source.length) {
                val mapped = mapping.originalToTransformed(offset)
                assertTrue(
                    mapped in 0..length,
                    "originalToTransformed($offset)=$mapped outside 0..$length for \"$source\""
                )
            }
            for (offset in 0..length) {
                val mapped = mapping.transformedToOriginal(offset)
                assertTrue(
                    mapped in 0..source.length,
                    "transformedToOriginal($offset)=$mapped outside 0..${source.length} for \"$source\""
                )
            }
        }
    }

    /** A mapping that ever goes backwards would break the binary-search inverse. */
    @Test
    fun `the mapping never goes backwards`() {
        val sources = listOf("**a __b__ c**", "_i_ **b** [x](https://e.com)", "____", "***x***")

        for (source in sources) {
            val mapping = RichTextParser.buildOffsetMapping(source)
            var previous = 0
            for (offset in 0..source.length) {
                val mapped = mapping.originalToTransformed(offset)
                assertTrue(mapped >= previous, "went backwards at $offset in \"$source\"")
                previous = mapped
            }
        }
    }

    /** Nothing may be dropped: every visible character has to come from somewhere. */
    @Test
    fun `no visible text is lost to nesting`() {
        val transformed = RichTextParser.toTransformedText(
            "**keep __all__ of _this_ text**",
            contentColor = Color.Black
        )

        assertEquals("keep all of this text", transformed.text.text)
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
