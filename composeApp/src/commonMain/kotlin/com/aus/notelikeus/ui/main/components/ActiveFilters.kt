package com.aus.notelikeus.ui.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.aus.notelikeus.domain.model.DateRange
import com.aus.notelikeus.domain.model.DateRangeSummary
import com.aus.notelikeus.domain.model.summarizeDateRange
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.ui.theme.NOTE_COLOR_OPTIONS
import com.aus.notelikeus.util.DateUtils
import com.aus.notelikeus.ui.theme.noteColorName
import com.aus.notelikeus.ui.theme.noteColorPaletteIndex
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.date_before
import notelikeus.composeapp.generated.resources.date_last_30_days
import notelikeus.composeapp.generated.resources.date_last_7_days
import notelikeus.composeapp.generated.resources.date_since
import notelikeus.composeapp.generated.resources.date_today
import notelikeus.composeapp.generated.resources.flag_has_checklist
import notelikeus.composeapp.generated.resources.flag_has_links
import notelikeus.composeapp.generated.resources.flag_has_reminder
import notelikeus.composeapp.generated.resources.flag_has_unchecked
import notelikeus.composeapp.generated.resources.flag_pinned
import notelikeus.composeapp.generated.resources.flag_reminder_overdue
import notelikeus.composeapp.generated.resources.flag_unlabeled
import notelikeus.composeapp.generated.resources.flag_untitled
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One active filter, named and removable.
 *
 * [remove] returns the query with just this dimension dropped, so the chip does not need to know
 * how the query is stored — tapping it produces the next query rather than a mutation instruction.
 */
data class ActiveFilter(
    val key: String,
    val label: String,
    val remove: (NoteQuery) -> NoteQuery
)

fun flagLabelRes(flag: NoteFlag): StringResource = when (flag) {
    NoteFlag.PINNED -> Res.string.flag_pinned
    NoteFlag.HAS_REMINDER -> Res.string.flag_has_reminder
    NoteFlag.REMINDER_OVERDUE -> Res.string.flag_reminder_overdue
    NoteFlag.HAS_CHECKLIST -> Res.string.flag_has_checklist
    NoteFlag.HAS_UNCHECKED_ITEMS -> Res.string.flag_has_unchecked
    NoteFlag.HAS_LINKS -> Res.string.flag_has_links
    NoteFlag.UNTITLED -> Res.string.flag_untitled
    NoteFlag.UNLABELED -> Res.string.flag_unlabeled
}

/**
 * The chips for everything currently narrowing the list.
 *
 * Free text is deliberately absent: it is already visible in the search box, and showing it twice
 * would give the user two places to clear the same thing and a choice about which one is real.
 *
 * Colours are grouped by palette entry. `query.colors` holds both the light and the dark variant
 * of each chosen colour so a note saved under either theme matches, which would otherwise render
 * as two identical "Green" chips.
 */
@Composable
fun activeFilters(query: NoteQuery, allLabels: List<Label>): List<ActiveFilter> {
    val result = mutableListOf<ActiveFilter>()

    query.labels.forEach { id ->
        val name = allLabels.firstOrNull { it.id == id }?.name ?: return@forEach
        result += ActiveFilter(
            key = "label-$id",
            label = name,
            remove = { it.copy(labels = it.labels - id) }
        )
    }

    val seenPaletteIndexes = mutableSetOf<Int>()
    query.colors.forEach { argb ->
        val index = noteColorPaletteIndex(Color(argb.toLong() and 0xffffffffL))
        if (index >= 0 && !seenPaletteIndexes.add(index)) return@forEach
        val option = NOTE_COLOR_OPTIONS.getOrNull(index)
        val siblings = setOfNotNull(
            option?.light?.takeIf { it != Color.Transparent }?.toArgb(),
            option?.dark?.takeIf { it != Color.Transparent }?.toArgb()
        ).ifEmpty { setOf(argb) }
        result += ActiveFilter(
            key = "color-${if (index >= 0) index else argb}",
            label = noteColorName(Color(argb.toLong() and 0xffffffffL)),
            // Removes both variants: they were added together and mean one choice.
            remove = { q -> q.copy(colors = q.colors - siblings) }
        )
    }

    query.flags.forEach { flag ->
        result += ActiveFilter(
            key = "flag-$flag",
            label = stringResource(flagLabelRes(flag)),
            remove = { it.copy(flags = it.flags - flag) }
        )
    }

    query.dateRange?.let { range ->
        result += ActiveFilter(
            key = "date",
            label = dateRangeLabel(range),
            remove = { it.copy(dateRange = null) }
        )
    }

    return result
}

/**
 * Words for what a range means. The deciding is done by [summarizeDateRange]; this only renders.
 *
 * It used to subtract the bounds and bucket the difference, which overflows in both directions
 * because both bounds are sentinels in the ordinary case -- so `before:` chips read "Today" and
 * every preset read "106751970482 d". Keeping the arithmetic out of a composable is what makes it
 * testable, and it is now covered by DateRangeSummaryTest.
 */
@Composable
private fun dateRangeLabel(range: DateRange): String {
    val todayStart = remember { DateUtils.startOfDay(DateUtils.currentTimeMillis()) }
    return when (val summary = summarizeDateRange(range, todayStart)) {
        DateRangeSummary.Today -> stringResource(Res.string.date_today)
        DateRangeSummary.Last7Days -> stringResource(Res.string.date_last_7_days)
        DateRangeSummary.Last30Days -> stringResource(Res.string.date_last_30_days)
        is DateRangeSummary.Before ->
            stringResource(Res.string.date_before, DateUtils.formatDateTime(summary.toExclusive))
        is DateRangeSummary.Since ->
            stringResource(Res.string.date_since, DateUtils.formatDateTime(summary.fromInclusive))
        is DateRangeSummary.Spanning -> "${summary.days} d"
    }
}
