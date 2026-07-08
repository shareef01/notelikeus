package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

object RichTextParser {

  private val boldPattern = Regex("""\*\*(.+?)\*\*""")
  private val italicPattern = Regex("""_(.+?)_""")

  fun parse(
      text: String,
      contentColor: Color,
      highlightColor: Color = Color.Transparent,
      searchQuery: String = ""
  ): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")

    val segments = splitIntoSegments(text)
    return buildAnnotatedString {
      segments.forEach { segment ->
        val style = when (segment.style) {
          SegmentStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold, color = contentColor)
          SegmentStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic, color = contentColor)
          SegmentStyle.Normal -> SpanStyle(color = contentColor)
        }
        appendStyledSegment(segment.text, style, contentColor, highlightColor, searchQuery)
      }
    }
  }

  private fun splitIntoSegments(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var index = 0

    while (index < text.length) {
      val boldMatch = boldPattern.matchAt(text, index)
      if (boldMatch != null) {
        segments += TextSegment(boldMatch.groupValues[1], SegmentStyle.Bold)
        index = boldMatch.range.last + 1
        continue
      }

      val italicMatch = italicPattern.matchAt(text, index)
      if (italicMatch != null) {
        segments += TextSegment(italicMatch.groupValues[1], SegmentStyle.Italic)
        index = italicMatch.range.last + 1
        continue
      }

      val nextBold = boldPattern.find(text, index)?.range?.first ?: text.length
      val nextItalic = italicPattern.find(text, index)?.range?.first ?: text.length
      val nextSpecial = minOf(nextBold, nextItalic)
      segments += TextSegment(text.substring(index, nextSpecial), SegmentStyle.Normal)
      index = nextSpecial
    }

    return segments
  }

  private fun AnnotatedString.Builder.appendStyledSegment(
      text: String,
      baseStyle: SpanStyle,
      contentColor: Color,
      highlightColor: Color,
      searchQuery: String
  ) {
    if (searchQuery.isEmpty() || !text.contains(searchQuery, ignoreCase = true)) {
      withStyle(baseStyle) { append(text) }
      return
    }

    var start = 0
    while (start < text.length) {
      val matchIndex = text.indexOf(searchQuery, start, ignoreCase = true)
      if (matchIndex == -1) {
        withStyle(baseStyle) { append(text.substring(start)) }
        break
      }
      if (matchIndex > start) {
        withStyle(baseStyle) { append(text.substring(start, matchIndex)) }
      }
      withStyle(
          baseStyle + SpanStyle(
              background = highlightColor,
              fontWeight = FontWeight.Bold,
              color = contentColor
          )
      ) {
        append(text.substring(matchIndex, matchIndex + searchQuery.length))
      }
      start = matchIndex + searchQuery.length
    }
  }

  private data class TextSegment(val text: String, val style: SegmentStyle)

  private enum class SegmentStyle {
    Normal, Bold, Italic
  }
}
