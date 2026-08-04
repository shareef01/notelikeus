package com.aus.notelikeus.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Smart Editor Intelligence
 * Intercepts text input to provide automatic Markdown structures.
 */
object SmartTextProcessor {

    data class ProcessingResult(
        val value: TextFieldValue,
        val structureChanged: Boolean = false
    )

    private val numberedListRegex = Regex("""^(\d+)\.\s""")

    fun process(current: TextFieldValue, previous: TextFieldValue): ProcessingResult {
        // Only process if user added a character or pressed enter
        if (current.text.length <= previous.text.length) return ProcessingResult(current)
        
        val addedText = current.text.substring(previous.selection.start, current.selection.start)
        
        // 1. Auto-List Detection (* or - or 1. at start of line)
        if (addedText == " ") {
            val lineStart = current.text.lastIndexOf('\n', startIndex = current.selection.start - 2).let {
                if (it == -1) 0 else it + 1
            }
            val linePrefix = current.text.substring(lineStart, current.selection.start)
            
            // Bullet conversion
            if (linePrefix == "* " || linePrefix == "- ") {
                val newText = current.text.replaceRange(lineStart, current.selection.start, "• ")
                return ProcessingResult(
                    current.copy(
                        text = newText,
                        selection = TextRange(lineStart + 2)
                    )
                )
            }

            // Checklist conversion trigger
            if (linePrefix == "[] " || linePrefix == "[ ] ") {
                return ProcessingResult(current, structureChanged = true)
            }
        }

        // 2. Smart Line Continuation (Enter)
        if (addedText == "\n") {
            val lastLineStart = current.text.lastIndexOf('\n', startIndex = current.selection.start - 2).let {
                if (it == -1) 0 else it + 1
            }
            val lastLine = current.text.substring(lastLineStart, current.selection.start - 1)
            
            // Bullet continuation
            if (lastLine.startsWith("• ")) {
                if (lastLine.trim() == "•") {
                    // Smart Exit: User pressed Enter on empty bullet, remove bullet
                    val newText = current.text.replaceRange(lastLineStart, current.selection.start, "\n")
                    return ProcessingResult(current.copy(text = newText, selection = TextRange(lastLineStart + 1)))
                }
                val newText = current.text.replaceRange(current.selection.start, current.selection.start, "• ")
                return ProcessingResult(current.copy(text = newText, selection = TextRange(current.selection.start + 2)))
            }

            // Numbered List continuation
            val numberedMatch = numberedListRegex.find(lastLine)
            if (numberedMatch != null) {
                if (lastLine.trim() == "${numberedMatch.groupValues[1]}.") {
                    // Smart Exit: Empty numbered item, remove prefix
                    val newText = current.text.replaceRange(lastLineStart, current.selection.start, "\n")
                    return ProcessingResult(current.copy(text = newText, selection = TextRange(lastLineStart + 1)))
                }
                val nextNum = numberedMatch.groupValues[1].toInt() + 1
                val prefix = "$nextNum. "
                val newText = current.text.replaceRange(current.selection.start, current.selection.start, prefix)
                return ProcessingResult(current.copy(text = newText, selection = TextRange(current.selection.start + prefix.length)))
            }
        }

        return ProcessingResult(current)
    }
}
