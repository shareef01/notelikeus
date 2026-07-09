package com.aus.notelikeus.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

object RichTextParser {

  private val boldPattern = Regex("""\*\*(.+?)\*\*""")
  private val italicPattern = Regex("""_(.+?)_""")
  private val linkPattern = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
  private val autoLinkPattern = Regex("""https?://[^\s)]+""")

  fun parse(
      text: String,
      contentColor: Color,
      highlightColor: Color = Color.Transparent,
      searchQuery: String = "",
      linkColor: Color = contentColor
  ): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")

    val segments = splitIntoSegments(text)
    return buildAnnotatedString {
      segments.forEach { segment ->
        if (segment.url != null) {
          withLink(LinkAnnotation.Url(segment.url)) {
            withStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )
            ) {
              append(segment.text)
            }
          }
        } else {
          val style = when (segment.style) {
            SegmentStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold, color = contentColor)
            SegmentStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic, color = contentColor)
            SegmentStyle.Normal -> SpanStyle(color = contentColor)
          }
          appendStyledSegment(segment.text, style, contentColor, highlightColor, searchQuery)
        }
      }
    }
  }

  fun toTransformedText(text: String, contentColor: Color): TransformedText {
    return TransformedText(
        text = parse(text, contentColor),
        offsetMapping = buildOffsetMapping(text)
    )
  }

  internal fun buildOffsetMapping(source: String): OffsetMapping {
    val originalToTransformed = IntArray(source.length + 1)
    var originalIndex = 0
    var transformedIndex = 0

    while (originalIndex < source.length) {
      originalToTransformed[originalIndex] = transformedIndex

      val boldMatch = boldPattern.matchAt(source, originalIndex)
      if (boldMatch != null) {
        mapHiddenMarkers(
            originalToTransformed = originalToTransformed,
            markerStart = boldMatch.range.first,
            markerLength = 2,
            inner = boldMatch.groupValues[1],
            closingMarkerStart = boldMatch.range.last - 1,
            transformedStart = transformedIndex
        )
        originalIndex = boldMatch.range.last + 1
        transformedIndex += boldMatch.groupValues[1].length
        continue
      }

      val linkMatch = linkPattern.matchAt(source, originalIndex)
      if (linkMatch != null) {
        val label = linkMatch.groupValues[1]
        val labelStart = linkMatch.range.first + 1
        originalToTransformed[linkMatch.range.first] = transformedIndex
        for (offset in label.indices) {
          originalToTransformed[labelStart + offset] = transformedIndex + offset
        }
        for (hidden in (labelStart + label.length)..linkMatch.range.last) {
          originalToTransformed[hidden] = transformedIndex + label.length
        }
        originalIndex = linkMatch.range.last + 1
        transformedIndex += label.length
        continue
      }

      val italicMatch = italicPattern.matchAt(source, originalIndex)
      if (italicMatch != null) {
        mapHiddenMarkers(
            originalToTransformed = originalToTransformed,
            markerStart = italicMatch.range.first,
            markerLength = 1,
            inner = italicMatch.groupValues[1],
            closingMarkerStart = italicMatch.range.last,
            transformedStart = transformedIndex
        )
        originalIndex = italicMatch.range.last + 1
        transformedIndex += italicMatch.groupValues[1].length
        continue
      }

      originalToTransformed[originalIndex] = transformedIndex
      originalIndex++
      transformedIndex++
    }
    originalToTransformed[source.length] = transformedIndex

    val displayLength = transformedIndex
    return object : OffsetMapping {
      override fun originalToTransformed(offset: Int): Int {
        return originalToTransformed[offset.coerceIn(0, source.length)]
      }

      override fun transformedToOriginal(offset: Int): Int {
        val target = offset.coerceIn(0, displayLength)
        var low = 0
        var high = source.length
        while (low < high) {
          val mid = (low + high) / 2
          if (originalToTransformed[mid] < target) {
            low = mid + 1
          } else {
            high = mid
          }
        }
        return low.coerceIn(0, source.length)
      }
    }
  }

  private fun mapHiddenMarkers(
      originalToTransformed: IntArray,
      markerStart: Int,
      markerLength: Int,
      inner: String,
      closingMarkerStart: Int,
      transformedStart: Int
  ) {
    for (marker in 0 until markerLength) {
      originalToTransformed[markerStart + marker] = transformedStart
    }
    val innerStart = markerStart + markerLength
    for (offset in inner.indices) {
      originalToTransformed[innerStart + offset] = transformedStart + offset
    }
    originalToTransformed[closingMarkerStart] = transformedStart + inner.length
    if (closingMarkerStart + 1 < originalToTransformed.size) {
      originalToTransformed[closingMarkerStart + 1] = transformedStart + inner.length
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

      val linkMatch = linkPattern.matchAt(text, index)
      if (linkMatch != null) {
        segments += TextSegment(
            text = linkMatch.groupValues[1],
            style = SegmentStyle.Normal,
            url = linkMatch.groupValues[2]
        )
        index = linkMatch.range.last + 1
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
      val nextLink = linkPattern.find(text, index)?.range?.first ?: text.length
      val nextSpecial = minOf(nextBold, nextItalic, nextLink)
      addPlainSegments(text.substring(index, nextSpecial), segments)
      index = nextSpecial
    }

    return segments
  }

  private fun addPlainSegments(text: String, segments: MutableList<TextSegment>) {
    if (text.isEmpty()) return

    var index = 0
    while (index < text.length) {
      val urlMatch = autoLinkPattern.find(text, index)
      if (urlMatch == null) {
        segments += TextSegment(text.substring(index), SegmentStyle.Normal)
        return
      }
      if (urlMatch.range.first > index) {
        segments += TextSegment(text.substring(index, urlMatch.range.first), SegmentStyle.Normal)
      }
      val url = urlMatch.value
      segments += TextSegment(url, SegmentStyle.Normal, url = url)
      index = urlMatch.range.last + 1
    }
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

  private data class TextSegment(
      val text: String,
      val style: SegmentStyle,
      val url: String? = null
  )

  private enum class SegmentStyle {
    Normal, Bold, Italic
  }
}
