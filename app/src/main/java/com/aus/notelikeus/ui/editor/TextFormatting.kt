package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

object TextFormatting {

    fun wrapSelection(value: TextFieldValue, marker: String): TextFieldValue {
        val selection = value.selection
        if (selection.collapsed) return value

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
}
