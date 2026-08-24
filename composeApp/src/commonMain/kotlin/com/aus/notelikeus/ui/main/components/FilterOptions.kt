package com.aus.notelikeus.ui.main.components

import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.DateRange
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.query.NoteQueryMatcher
import com.aus.notelikeus.ui.theme.noteColorCounterpart
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.date_any
import notelikeus.composeapp.generated.resources.date_field_created
import notelikeus.composeapp.generated.resources.date_field_edited
import notelikeus.composeapp.generated.resources.date_field_reminder
import notelikeus.composeapp.generated.resources.date_last_30_days
import notelikeus.composeapp.generated.resources.date_last_7_days
import notelikeus.composeapp.generated.resources.date_today
import notelikeus.composeapp.generated.resources.scope_active
import notelikeus.composeapp.generated.resources.scope_all
import notelikeus.composeapp.generated.resources.scope_archive
import notelikeus.composeapp.generated.resources.scope_trash
import org.jetbrains.compose.resources.StringResource

fun scopeLabelRes(scope: NoteScope): StringResource = when (scope) {
    NoteScope.ACTIVE -> Res.string.scope_active
    NoteScope.ARCHIVE -> Res.string.scope_archive
    NoteScope.TRASH -> Res.string.scope_trash
    NoteScope.ALL -> Res.string.scope_all
}

fun dateFieldLabelRes(field: DateField): StringResource = when (field) {
    DateField.CREATED -> Res.string.date_field_created
    DateField.EDITED -> Res.string.date_field_edited
    DateField.REMINDER -> Res.string.date_field_reminder
}

private const val DAY = 86_400_000L

/**
 * The date windows the sheet offers.
 *
 * All open-ended forward: "last 7 days" means the last seven days *up to now*, not a fixed week,
 * so the answer does not change meaning at midnight while someone is looking at it. The upper
 * bound is [Long.MAX_VALUE] rather than "today + 1" so a note edited a moment ago is included
 * without the range needing to be recomputed as time passes.
 */
enum class DatePreset(val labelRes: StringResource, private val days: Int?) {
    ANY(Res.string.date_any, null),
    TODAY(Res.string.date_today, 0),
    LAST_7(Res.string.date_last_7_days, 7),
    LAST_30(Res.string.date_last_30_days, 30);

    fun range(todayStart: Long): DateRange? = when (days) {
        null -> null
        else -> DateRange(todayStart - days * DAY, Long.MAX_VALUE)
    }
}

/** Toggles one colour, carrying its light/dark counterpart with it — they are one choice. */
fun NoteQuery.toggleColor(argb: Int): NoteQuery {
    val siblings = setOfNotNull(argb, noteColorCounterpart(argb)?.takeIf { it != argb })
    return if (argb in colors) copy(colors = colors - siblings) else copy(colors = colors + siblings)
}

fun NoteQuery.toggleFlag(flag: NoteFlag): NoteQuery =
    if (flag in flags) copy(flags = flags - flag) else copy(flags = flags + flag)

fun NoteQuery.toggleLabel(id: Long): NoteQuery =
    if (id in labels) copy(labels = labels - id) else copy(labels = labels + id)

/**
 * How many notes each option would leave visible, and how many are visible now.
 *
 * Counted by running the real matcher, not by estimating. A count that disagreed with the list it
 * describes would be worse than no count at all, and the only way to be sure it agrees is to ask
 * the same function the list asks.
 *
 * Each option is counted **as if it were the only change** — the current query with that one
 * dimension toggled — which is what makes "this would show nothing" a truthful statement about
 * what tapping it does.
 */
class FilterCounts(
    private val query: NoteQuery,
    private val notes: List<Note>,
    private val now: Long
) {
    /** Notes matching the query as it stands. */
    val current: Int = count(query)

    private val colorCounts = mutableMapOf<Int, Int>()
    private val flagCounts = mutableMapOf<NoteFlag, Int>()
    private val labelCounts = mutableMapOf<Long, Int>()

    fun forColor(argb: Int): Int = colorCounts.getOrPut(argb) { count(query.toggleColor(argb)) }

    fun forFlag(flag: NoteFlag): Int = flagCounts.getOrPut(flag) { count(query.toggleFlag(flag)) }

    fun forLabel(id: Long): Int = labelCounts.getOrPut(id) { count(query.toggleLabel(id)) }

    private fun count(candidate: NoteQuery): Int {
        val needles = NoteQueryMatcher.textNeedles(candidate.text)
        return notes.count { NoteQueryMatcher.matches(it, candidate, now, needles) }
    }
}
