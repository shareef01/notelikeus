package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

object TextFormatting {

    /**
     * Wraps the selection in [marker], or opens an empty pair at the cursor when there is none.
     *
     * The collapsed case used to return the value untouched, which made Bold and Italic dead
     * controls in the ordinary case: tap B with nothing selected -- which is what you do when you
     * are about to type something bold -- and absolutely nothing happened, with no selection to
     * explain why. Now it does what every other editor does and puts the cursor between a fresh
     * pair of markers, so the next thing typed is bold.
     */
    fun wrapSelection(value: TextFieldValue, marker: String): TextFieldValue {
        val selection = value.selection
        if (selection.collapsed) {
            val at = selection.start
            return TextFieldValue(
                text = value.text.replaceRange(at, at, marker + marker),
                selection = TextRange(at + marker.length)
            )
        }

        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        val selected = value.text.substring(start, end)
        val wrapped = "$marker$selected$marker"
        val newText = value.text.replaceRange(start, end, wrapped)
        val cursorStart = start + marker.length
        val cursorEnd = cursorStart + selected.length

        return TextFieldValue(
            text = newText,
            selection = TextRange(cursorStart, cursorEnd)
        )
    }

    fun prefixLinesWithBullet(value: TextFieldValue): TextFieldValue {
        val selection = value.selection
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)

        val lineStart = value.text.lastIndexOf('\n', startIndex = start - 1).let {
            if (it == -1) 0 else it + 1
        }
        val lineEnd = value.text.indexOf('\n', startIndex = end).let {
            if (it == -1) value.text.length else it
        }

        val block = value.text.substring(lineStart, lineEnd)
        val prefixed = block.lines().joinToString("\n") { line ->
            if (line.isBlank() || line.startsWith("• ")) line else "• $line"
        }
        val newText = value.text.replaceRange(lineStart, lineEnd, prefixed)

        return TextFieldValue(
            text = newText,
            selection = TextRange(lineStart, lineStart + prefixed.length)
        )
    }

    /**
     * Turns the selection into a markdown link, or inserts one labelled with the URL itself when
     * there is no selection.
     *
     * The collapsed case was the worst of the three dead controls, because it wasted work rather
     * than just doing nothing: the link dialog opened, you typed a URL, you confirmed, and the
     * note was unchanged. Inserting `[example.com](https://example.com)` gives a link that works
     * immediately and a label that can be edited into something better.
     */
    fun wrapAsLink(value: TextFieldValue, url: String): TextFieldValue {
        if (url.isBlank()) return value

        val selection = value.selection
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        val label = if (selection.collapsed) url.trim() else value.text.substring(start, end)
        val normalizedUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
        val link = "[$label]($normalizedUrl)"
        val newText = value.text.replaceRange(start, end, link)

        return TextFieldValue(
            text = newText,
            selection = TextRange(start + link.length)
        )
    }
}
