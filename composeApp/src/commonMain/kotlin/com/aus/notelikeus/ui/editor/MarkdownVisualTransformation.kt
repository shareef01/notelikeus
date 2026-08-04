package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class MarkdownVisualTransformation(
    private val contentColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        return RichTextParser.toTransformedText(text.text, contentColor)
    }

    override fun equals(other: Any?): Boolean {
        return other is MarkdownVisualTransformation && other.contentColor == contentColor
    }

    override fun hashCode(): Int = contentColor.hashCode()
}
