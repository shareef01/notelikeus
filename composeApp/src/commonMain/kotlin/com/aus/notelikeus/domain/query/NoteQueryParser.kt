package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteScope

/**
 * What a search string said, before anything has been resolved against the user's data.
 *
 * Label and colour arrive as **names**, not ids or ARGB values. The parser has no business
 * knowing which labels exist or what "green" is worth on the current theme — those live in the
 * repository and the palette respectively, and a parser that reached for them could not be tested
 * without both. The ViewModel resolves them; this only reads.
 *
 * [unknown] keeps anything operator-shaped that was not understood, so the UI can say so instead
 * of silently dropping it or, worse, searching for the literal text `labe:work`.
 */
data class ParsedQuery(
    val text: String = "",
    val labelNames: List<String> = emptyList(),
    val colorNames: List<String> = emptyList(),
    val flags: Set<NoteFlag> = emptySet(),
    val scope: NoteScope? = null,
    val dateField: DateField? = null,
    /** Exclusive upper bound, epoch millis. */
    val before: Long? = null,
    /** Inclusive lower bound, epoch millis. */
    val after: Long? = null,
    val unknown: List<String> = emptyList()
)

/**
 * Parses the operators the brief specifies out of a search string.
 *
 * `label:sec` `color:green` `is:pinned` `has:reminder` `before:2026-08-01` `after:yesterday`
 * `in:trash`
 *
 * Everything not recognised as an operator stays free text, so typing a colon in an ordinary
 * search — `ratio: 3:1`, `TODO: fix` — searches for it rather than being swallowed. That is the
 * property most easily broken here and the one most visible when broken.
 *
 * [dayStart] converts a whole-day offset from today into epoch millis at that day's local
 * midnight, and [dayStartOfDate] does the same for a civil date. Both are injected rather than
 * computed, because midnight is a timezone question and this file has no business answering it;
 * it also lets the tests pin dates without pinning a clock.
 *
 * Two functions rather than one because deriving the second from the first is exactly the bug
 * this shape exists to prevent: an ISO date used to be turned into a day offset by dividing
 * `dayStart(0)` by 86,400,000, but that value carries a timezone — local midnight east of UTC
 * falls on the previous UTC day — so the division answered a day early and every typed date
 * resolved one day late for roughly two thirds of the world.
 */
object NoteQueryParser {

    /** Values usable with `is:`, beyond the flags. */
    private val SCOPE_WORDS = mapOf(
        "archived" to NoteScope.ARCHIVE,
        "archive" to NoteScope.ARCHIVE,
        "trashed" to NoteScope.TRASH,
        "trash" to NoteScope.TRASH,
        "deleted" to NoteScope.TRASH
    )

    private val IS_FLAGS = mapOf(
        "pinned" to NoteFlag.PINNED,
        "untitled" to NoteFlag.UNTITLED,
        "unlabeled" to NoteFlag.UNLABELED,
        "unlabelled" to NoteFlag.UNLABELED,
        "overdue" to NoteFlag.REMINDER_OVERDUE
    )

    private val HAS_FLAGS = mapOf(
        "reminder" to NoteFlag.HAS_REMINDER,
        "reminders" to NoteFlag.HAS_REMINDER,
        "checklist" to NoteFlag.HAS_CHECKLIST,
        "unchecked" to NoteFlag.HAS_UNCHECKED_ITEMS,
        "link" to NoteFlag.HAS_LINKS,
        "links" to NoteFlag.HAS_LINKS
    )

    private val IN_SCOPES = mapOf(
        "trash" to NoteScope.TRASH,
        "archive" to NoteScope.ARCHIVE,
        "all" to NoteScope.ALL,
        "notes" to NoteScope.ACTIVE,
        "active" to NoteScope.ACTIVE
    )

    /** The operator prefixes, so free text containing a colon is left alone. */
    private val KNOWN_PREFIXES = setOf("label", "color", "colour", "is", "has", "before", "after", "in")

    fun parse(
        input: String,
        dayStart: (daysFromToday: Int) -> Long,
        dayStartOfDate: (year: Int, month: Int, day: Int) -> Long?
    ): ParsedQuery {
        var result = ParsedQuery()
        val freeText = StringBuilder()

        for (token in tokenize(input)) {
            val colon = token.indexOf(':')
            val prefix = if (colon > 0) token.substring(0, colon).lowercase() else null
            if (prefix == null || prefix !in KNOWN_PREFIXES) {
                if (freeText.isNotEmpty()) freeText.append(' ')
                freeText.append(token)
                continue
            }
            val value = unquote(token.substring(colon + 1)).trim()
            if (value.isEmpty()) {
                // `label:` with nothing after it. Not free text -- the user is mid-thought and
                // searching for the literal string "label:" is never what they meant.
                result = result.copy(unknown = result.unknown + token)
                continue
            }
            result = applyOperator(result, prefix, value, dayStart, dayStartOfDate) ?: result.copy(
                unknown = result.unknown + token
            )
        }

        return result.copy(text = freeText.toString().trim())
    }

    private fun applyOperator(
        current: ParsedQuery,
        prefix: String,
        value: String,
        dayStart: (Int) -> Long,
        dayStartOfDate: (Int, Int, Int) -> Long?
    ): ParsedQuery? {
        val lower = value.lowercase()
        return when (prefix) {
            "label" -> current.copy(labelNames = current.labelNames + value)
            "color", "colour" -> current.copy(colorNames = current.colorNames + lower)
            "is" -> {
                IS_FLAGS[lower]?.let { return current.copy(flags = current.flags + it) }
                SCOPE_WORDS[lower]?.let { return current.copy(scope = it) }
                null
            }
            "has" -> HAS_FLAGS[lower]?.let { current.copy(flags = current.flags + it) }
            "in" -> IN_SCOPES[lower]?.let { current.copy(scope = it) }
            "before" -> parseDate(lower, dayStart, dayStartOfDate)?.let {
                current.copy(before = it, dateField = current.dateField ?: DateField.EDITED)
            }
            "after" -> parseDate(lower, dayStart, dayStartOfDate)?.let {
                // `after:monday` means from the start of that day onwards, so the boundary is that
                // day's midnight rather than the following one.
                current.copy(after = it, dateField = current.dateField ?: DateField.EDITED)
            }
            else -> null
        }
    }

    /**
     * `today`, `yesterday`, `week`, `month`, or an ISO `YYYY-MM-DD`.
     *
     * Returns the epoch millis of that day's start; callers decide whether that is an inclusive
     * lower bound or an exclusive upper one.
     */
    private fun parseDate(
        value: String,
        dayStart: (Int) -> Long,
        dayStartOfDate: (Int, Int, Int) -> Long?
    ): Long? = when (value) {
        "today" -> dayStart(0)
        "yesterday" -> dayStart(-1)
        "tomorrow" -> dayStart(1)
        "week" -> dayStart(-7)
        "month" -> dayStart(-30)
        "year" -> dayStart(-365)
        else -> parseIsoDate(value, dayStartOfDate)
    }

    /**
     * An ISO date, handed straight to [dayStartOfDate].
     *
     * Nothing is computed from it here. The shape of the operator — three integers — is all this
     * function decides; where that date's midnight falls is the caller's answer to give, and a
     * date that does not exist comes back null so the operator is recorded as unrecognised rather
     * than resolved to a day the user did not type.
     */
    private fun parseIsoDate(value: String, dayStartOfDate: (Int, Int, Int) -> Long?): Long? {
        val parts = value.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return dayStartOfDate(year, month, day)
    }

    /**
     * Splits on whitespace, keeping `label:"two words"` together.
     *
     * Quotes are only meaningful straight after a colon; a quote in ordinary text is just a
     * character, because notes are full of apostrophes and quoted speech.
     */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in input) {
            when {
                c == '"' && (inQuotes || current.endsWith(":")) -> {
                    inQuotes = !inQuotes
                    current.append(c)
                }
                c.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1)
        } else {
            value.removePrefix("\"")
        }
}

