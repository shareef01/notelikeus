package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.LabelMatch
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.model.NoteSortOrder

/**
 * Whether a note satisfies a query, and how a matching set is ordered.
 *
 * Pure and Android-free by design: the brief asks for query-to-results to be a single testable
 * function, and the previous arrangement — filtering inside a ViewModel that also owned a
 * state machine — could only be tested through that machine.
 *
 * The database narrows first where it can (scope, text, colour), so in practice this runs over an
 * already-reduced set. It still evaluates every dimension itself, because it is also the
 * definition the tests and the live result count use, and a matcher that assumed SQL had already
 * applied half the query would be two definitions that could disagree.
 */
object NoteQueryMatcher {

    /**
     * Note colours are stored as raw ARGB and the palette has a light and a dark variant of each
     * hue. Selecting "green" has to match a note saved on either theme, so the caller expands its
     * selection to every equivalent value and this is a plain set membership test — the expansion
     * lives with the palette, which is the only place that knows what is equivalent.
     */
    fun matches(note: Note, query: NoteQuery, now: Long): Boolean =
        matches(note, query, now, textNeedles(query.text))

    /**
     * The per-note test, with the query's text needles hoisted out.
     *
     * [apply] prepares them once for the whole list; the single-note overload above prepares them
     * for one call, which is what tests and one-off checks want.
     */
    fun matches(
        note: Note,
        query: NoteQuery,
        now: Long,
        needles: List<Pair<String, String>>
    ): Boolean =
        matchesScope(note, query.scope) &&
            matchesText(note, needles) &&
            matchesColors(note, query.colors) &&
            matchesLabels(note, query) &&
            matchesFlags(note, query.flags, now) &&
            matchesDate(note, query, now)

    private fun matchesScope(note: Note, scope: NoteScope): Boolean = when (scope) {
        NoteScope.ACTIVE -> !note.isArchived && !note.isTrashed
        NoteScope.ARCHIVE -> note.isArchived && !note.isTrashed
        NoteScope.TRASH -> note.isTrashed
        NoteScope.ALL -> true
    }

    /**
     * Every token must match a token *prefix* somewhere in the note, in any order.
     *
     * Order-independence is what makes "milk bread" find a note titled "Bread and milk". Prefix
     * rather than substring is a deliberate narrowing of the old behaviour: `contains` matched
     * mid-word, so "ote" found "notes", which is almost never what someone means and made short
     * queries useless.
     *
     * Scanned rather than split. Splitting the haystack allocates a list per note per keystroke,
     * which at a few thousand notes is the whole cost of the filter; a token matches at a word
     * boundary if the text starts with it or contains it preceded by a space, and neither
     * allocates. [needles] is prepared once per query by [textNeedles] for the same reason.
     */
    private fun matchesText(note: Note, needles: List<Pair<String, String>>): Boolean {
        if (needles.isEmpty()) return true
        // Null means not yet indexed, so fold on the spot: never "matches nothing".
        val haystack = note.searchText ?: note.searchableText()
        return needles.all { (token, spaced) ->
            haystack.startsWith(token) || haystack.contains(spaced)
        }
    }

    /** Query tokens paired with their space-prefixed form, prepared once rather than per note. */
    internal fun textNeedles(text: String): List<Pair<String, String>> =
        searchTokens(text).map { it to " $it" }

    private fun matchesColors(note: Note, colors: Set<Int>): Boolean =
        colors.isEmpty() || note.color in colors

    private fun matchesLabels(note: Note, query: NoteQuery): Boolean {
        if (query.labels.isEmpty()) return true
        val own = note.labels.mapNotNull { it.id }.toSet()
        return when (query.labelMatch) {
            LabelMatch.ANY -> own.any { it in query.labels }
            LabelMatch.ALL -> own.containsAll(query.labels)
        }
    }

    private fun matchesFlags(note: Note, flags: Set<NoteFlag>, now: Long): Boolean =
        flags.all { flag -> note.satisfies(flag, now) }

    private fun matchesDate(note: Note, query: NoteQuery, now: Long): Boolean {
        val range = query.dateRange ?: return true
        val value = when (query.dateField) {
            // The schema has one timestamp per note and it moves on edit, so CREATED and EDITED
            // read the same column. Kept as separate options because the *filter* is meaningful
            // and a created-at column can be added later without the query model changing shape.
            DateField.CREATED, DateField.EDITED -> note.timestamp
            DateField.REMINDER -> note.reminderTimestamp ?: return false
        }
        return value in range
    }

    /**
     * Orders a matching set.
     *
     * Pinned notes lead in every mode. That is not a sort key the user picked, it is what pinning
     * means, so it is applied outside the comparator rather than as its first term.
     */
    fun sort(notes: List<Note>, sort: NoteSortOrder): List<Note> {
        val comparator: Comparator<Note> = when (sort) {
            // MANUAL is the stored `position`, with timestamp only as a tie-break for rows that
            // have never been dragged and therefore share position 0.
            NoteSortOrder.MANUAL -> compareBy<Note> { it.position }.thenByDescending { it.timestamp }
            NoteSortOrder.NEWEST -> compareByDescending { it.timestamp }
            NoteSortOrder.OLDEST -> compareBy { it.timestamp }
        }
        val (pinned, rest) = notes.partition { it.isPinned }
        return pinned.sortedWith(comparator) + rest.sortedWith(comparator)
    }

    /** Match then order, which is what every caller actually wants. */
    fun apply(notes: List<Note>, query: NoteQuery, now: Long): List<Note> {
        val needles = textNeedles(query.text)
        return sort(notes.filter { matches(it, query, now, needles) }, query.sort)
    }
}

/** Everything about a note that free text searches, in one string. */
internal fun Note.searchableText(): String = buildSearchText(
    title = title,
    content = content,
    checklistTexts = checklist.map { it.text },
    labelNames = labels.map { it.name }
)

/**
 * Whether a note has the property [flag] describes.
 *
 * Kept next to the matcher rather than on `Note` so the domain model stays a data class: these are
 * questions the query system asks, not facts the note carries.
 */
internal fun Note.satisfies(flag: NoteFlag, now: Long): Boolean = when (flag) {
    NoteFlag.PINNED -> isPinned
    NoteFlag.HAS_REMINDER -> reminderTimestamp != null
    NoteFlag.REMINDER_OVERDUE -> reminderTimestamp?.let { it <= now } == true
    NoteFlag.HAS_CHECKLIST -> checklist.isNotEmpty()
    NoteFlag.HAS_UNCHECKED_ITEMS -> checklist.any { !it.isChecked }
    NoteFlag.HAS_LINKS -> containsLink()
    NoteFlag.UNTITLED -> title.isBlank()
    NoteFlag.UNLABELED -> labels.isEmpty()
}

/**
 * A cheap scheme check rather than a URL parser.
 *
 * The alternative is a regex over every note's whole body on every recompute, to answer a filter
 * almost nobody turns on. `http://`, `https://` and `www.` is what people actually paste.
 */
private fun Note.containsLink(): Boolean {
    val haystack = "$title\n$content"
    return haystack.contains("http://", ignoreCase = true) ||
        haystack.contains("https://", ignoreCase = true) ||
        haystack.contains("www.", ignoreCase = true)
}
