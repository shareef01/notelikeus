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

/**
 * Renders the small markdown subset notes are written in.
 *
 * **One traversal produces everything.** The displayed text, the styles laid over it, and the
 * original-to-transformed offset map all come out of [render] together. That is not tidiness: the
 * editor feeds [toTransformedText] to a real text field, and Compose throws if the mapping and the
 * text disagree about length. Two independent walks -- which is what this was -- could drift apart
 * on any input nobody had thought to test, and the symptom is a crash while typing rather than
 * something visibly wrong.
 *
 * Building them together makes that class of bug unrepresentable instead of merely tested for.
 */
object RichTextParser {

  private val boldPattern = Regex("""\*\*(.+?)\*\*""")

  /**
   * The other CommonMark spelling of bold.
   *
   * The app's own toolbar writes `**`, so this is for text that arrived from somewhere else --
   * pasted from another editor, or typed by someone who writes markdown by hand. Without it the
   * single-underscore italic rule ate the *outer* pair of `__bold__` and left the inner two
   * visible. Found on a real note.
   */
  private val boldUnderscorePattern = Regex("""__(.+?)__""")
  private val italicPattern = Regex("""_(.+?)_""")
  private val linkPattern = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
  private val autoLinkPattern = Regex("""https?://[^\s)]+""")

  /**
   * How deep emphasis may nest before the rest is treated as plain text.
   *
   * Real notes nest one or two levels. The cap is here so that adversarial input -- a line of two
   * hundred asterisks -- cannot recurse the renderer into a stack overflow.
   */
  private const val MaxNesting = 8

  fun parse(
      text: String,
      contentColor: Color,
      highlightColor: Color = Color.Transparent,
      searchQuery: String = "",
      linkColor: Color = contentColor,
      linksClickable: Boolean = true
  ): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return render(text).toAnnotatedString(
        contentColor = contentColor,
        highlightColor = highlightColor,
        searchQuery = searchQuery,
        linkColor = linkColor,
        linksClickable = linksClickable
    )
  }

  fun toTransformedText(text: String, contentColor: Color): TransformedText {
    if (text.length > 5000) {
        // Optimization: For very large notes, return plain text to keep typing responsive
        return TransformedText(AnnotatedString(text), OffsetMapping.Identity)
    }
    // Rendered once, so the text and the mapping handed to the text field are provably the same
    // walk rather than two that happen to agree.
    val rendered = render(text)
    return TransformedText(
        text = rendered.toAnnotatedString(contentColor = contentColor),
        offsetMapping = rendered.toOffsetMapping()
    )
  }

  internal fun buildOffsetMapping(source: String): OffsetMapping = render(source).toOffsetMapping()

  // ---- the single walk ----

  /** A run of displayed text and what is true of it. Offsets index the *displayed* string. */
  private data class Span(
      val start: Int,
      val end: Int,
      val bold: Boolean,
      val italic: Boolean,
      val url: String? = null
  )

  private class Rendered(
      val text: String,
      /**
       * Where each source offset lands in [text], including one past the end.
       *
       * Non-decreasing by construction -- it only ever records the current output length -- which
       * is what lets the inverse be a binary search.
       */
      val originalToTransformed: IntArray,
      val spans: List<Span>
  )

  private fun render(source: String): Rendered {
    val out = StringBuilder()
    val map = IntArray(source.length + 1)
    val spans = mutableListOf<Span>()

    /** Marker characters: they take up no room in the output, so they collapse to where it is. */
    fun hide(from: Int, until: Int) {
      for (index in from until until) map[index] = out.length
    }

    /** Copies source text through to the output, mapping each character to where it landed. */
    fun copy(from: Int, until: Int) {
      for (index in from until until) {
        map[index] = out.length
        out.append(source[index])
      }
    }

    fun plain(from: Int, until: Int, bold: Boolean, italic: Boolean) {
      var index = from
      while (index < until) {
        val link = autoLinkPattern.find(source, index)?.takeIf { it.range.first < until }
        if (link == null) {
          val start = out.length
          copy(index, until)
          if (out.length > start) spans += Span(start, out.length, bold, italic)
          return
        }
        if (link.range.first > index) {
          val start = out.length
          copy(index, link.range.first)
          spans += Span(start, out.length, bold, italic)
        }
        // A bare URL is its own label, so nothing is hidden -- it is styled, not rewritten.
        val linkEnd = minOf(link.range.last + 1, until)
        val start = out.length
        copy(link.range.first, linkEnd)
        spans += Span(start, out.length, bold, italic, source.substring(link.range.first, linkEnd))
        index = linkEnd
      }
    }

    fun emit(from: Int, until: Int, bold: Boolean, italic: Boolean, depth: Int) {
      if (depth > MaxNesting) {
        plain(from, until, bold, italic)
        return
      }
      var index = from
      while (index < until) {
        // A match may not run past the region it was found in, or an outer closing marker would be
        // swallowed by an inner rule and the two walks would disagree about what is hidden.
        fun MatchResult?.within() = this?.takeIf { it.range.last < until }

        val boldMatch = boldPattern.matchAt(source, index).within()
            ?: boldUnderscorePattern.matchAt(source, index).within()
        if (boldMatch != null) {
          hide(boldMatch.range.first, boldMatch.range.first + 2)
          // Recursing is the whole point: `**a __b__ c**` used to render bold with the inner
          // underscores still on screen, because a matched span was emitted as opaque text and
          // never looked at again.
          emit(boldMatch.range.first + 2, boldMatch.range.last - 1, true, italic, depth + 1)
          hide(boldMatch.range.last - 1, boldMatch.range.last + 1)
          index = boldMatch.range.last + 1
          continue
        }

        val linkMatch = linkPattern.matchAt(source, index).within()
        if (linkMatch != null) {
          val label = linkMatch.groupValues[1]
          val labelStart = linkMatch.range.first + 1
          hide(linkMatch.range.first, labelStart)
          val start = out.length
          copy(labelStart, labelStart + label.length)
          spans += Span(start, out.length, bold, italic, linkMatch.groupValues[2])
          hide(labelStart + label.length, linkMatch.range.last + 1)
          index = linkMatch.range.last + 1
          continue
        }

        val italicMatch = italicPattern.matchAt(source, index).within()
        if (italicMatch != null) {
          hide(italicMatch.range.first, italicMatch.range.first + 1)
          emit(italicMatch.range.first + 1, italicMatch.range.last, bold, true, depth + 1)
          hide(italicMatch.range.last, italicMatch.range.last + 1)
          index = italicMatch.range.last + 1
          continue
        }

        val next = nextSpecial(source, index, until)
        plain(index, next, bold, italic)
        index = next
      }
    }

    emit(0, source.length, bold = false, italic = false, depth = 0)
    map[source.length] = out.length
    return Rendered(out.toString(), map, spans)
  }

  /** The next offset in `[from, until)` where a marker could start, or `until`. */
  private fun nextSpecial(source: String, from: Int, until: Int): Int {
    if (from >= until) return until
    val candidates = listOf(
        boldPattern.find(source, from)?.range?.first,
        boldUnderscorePattern.find(source, from)?.range?.first,
        italicPattern.find(source, from)?.range?.first,
        linkPattern.find(source, from)?.range?.first
    )
    val next = candidates.filterNotNull().filter { it > from }.minOrNull() ?: until
    return minOf(next, until).coerceAtLeast(from + 1)
  }

  // ---- turning one render into the two things callers need ----

  private fun Rendered.toAnnotatedString(
      contentColor: Color,
      highlightColor: Color = Color.Transparent,
      searchQuery: String = "",
      linkColor: Color = contentColor,
      linksClickable: Boolean = true
  ): AnnotatedString = buildAnnotatedString {
    // Appended whole, then styled by offset. The displayed text is therefore literally the string
    // the offset map was built against, rather than a second assembly of the same pieces.
    append(text)

    // One merged style per run, not a base colour plus overlays. Every character is covered by
    // exactly one span (asserted by `every character of the output belongs to a span`), so a base
    // layer would be redundant -- and it would also make spanStyles.first() the colour rather than
    // the emphasis, which is not what a caller reading this list expects.
    spans.forEach { span ->
      addStyle(
          SpanStyle(
              color = if (span.url != null) linkColor else contentColor,
              fontWeight = if (span.bold) FontWeight.Bold else null,
              fontStyle = if (span.italic) FontStyle.Italic else null,
              textDecoration = if (span.url != null) TextDecoration.Underline else null
          ),
          span.start,
          span.end
      )
      if (span.url != null && linksClickable) {
        addLink(LinkAnnotation.Url(span.url), span.start, span.end)
      }
    }

    if (searchQuery.isNotEmpty()) {
      // Over the whole displayed string rather than per styled run, so a query spanning a style
      // boundary still highlights -- which the previous per-segment pass could not do.
      var start = text.indexOf(searchQuery, 0, ignoreCase = true)
      while (start >= 0) {
        addStyle(
            SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold, color = contentColor),
            start,
            start + searchQuery.length
        )
        start = text.indexOf(searchQuery, start + searchQuery.length, ignoreCase = true)
      }
    }
  }

  private fun Rendered.toOffsetMapping(): OffsetMapping {
    val sourceLength = originalToTransformed.size - 1
    val displayLength = text.length
    val map = originalToTransformed
    return object : OffsetMapping {
      override fun originalToTransformed(offset: Int): Int =
          map[offset.coerceIn(0, sourceLength)]

      override fun transformedToOriginal(offset: Int): Int {
        val target = offset.coerceIn(0, displayLength)
        var low = 0
        var high = sourceLength
        while (low < high) {
          val mid = (low + high) / 2
          if (map[mid] < target) low = mid + 1 else high = mid
        }
        return low.coerceIn(0, sourceLength)
      }
    }
  }
}
