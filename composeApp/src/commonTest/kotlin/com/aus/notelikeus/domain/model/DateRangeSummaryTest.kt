package com.aus.notelikeus.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

private const val DAY = 86_400_000L

/** Local midnight today, deliberately *not* on a UTC day boundary. */
private const val TODAY_START = 20_685L * DAY - 19_800_000L // UTC+05:30

/** How `DatePreset.range` builds every preset: open-ended forward from N days ago. */
private fun preset(daysBack: Int) = DateRange(TODAY_START - daysBack * DAY, Long.MAX_VALUE)

/**
 * The label a date chip shows.
 *
 * Every assertion here failed against the previous implementation, which subtracted the range's
 * bounds. Both bounds are sentinels in the ordinary case, so that subtraction overflowed signed
 * 64-bit arithmetic in both directions and there was no input the app could produce that it got
 * right. It survived because it lived inside a `@Composable` reading `stringResource`, where
 * nothing tested it.
 */
class DateRangeSummaryTest {

    // ---- what `before:` and `after:` produce ----

    @Test
    fun `an open-below range is described by its upper bound, not as Today`() {
        // `before:2026-08-20`. The old code computed `X - Long.MIN_VALUE`, which wraps negative,
        // matched the "<= 1 day" bucket, and labelled every such filter "Today".
        val range = DateRange(Long.MIN_VALUE, TODAY_START)
        assertEquals(DateRangeSummary.Before(TODAY_START), summarizeDateRange(range, TODAY_START))
    }

    @Test
    fun `an open-above range further back than any preset reports its start`() {
        // `after:` a year ago. The old code computed `Long.MAX_VALUE - Y` and rendered the raw day
        // count: "106751970482 d", about 292 million years.
        val start = TODAY_START - 365 * DAY
        val range = DateRange(start, Long.MAX_VALUE)
        assertEquals(DateRangeSummary.Since(start), summarizeDateRange(range, TODAY_START))
    }

    // ---- the presets, which are all open-above and were all mislabelled ----

    @Test
    fun `each preset reports itself`() {
        assertEquals(DateRangeSummary.Today, summarizeDateRange(preset(0), TODAY_START))
        assertEquals(DateRangeSummary.Last7Days, summarizeDateRange(preset(7), TODAY_START))
        assertEquals(DateRangeSummary.Last30Days, summarizeDateRange(preset(30), TODAY_START))
    }

    @Test
    fun `the preset boundaries fall on the preset, not past it`() {
        // 7 days back is still "Last 7 days"; 8 is not. Same at 30.
        assertEquals(DateRangeSummary.Last7Days, summarizeDateRange(preset(7), TODAY_START))
        assertEquals(DateRangeSummary.Last30Days, summarizeDateRange(preset(8), TODAY_START))
        assertEquals(DateRangeSummary.Last30Days, summarizeDateRange(preset(30), TODAY_START))
        assertEquals(
            DateRangeSummary.Since(TODAY_START - 31 * DAY),
            summarizeDateRange(preset(31), TODAY_START),
        )
    }

    @Test
    fun `a window starting later today still reads as Today`() {
        // Not a shape the sheet builds, but arithmetic that rounds the wrong way would land here
        // as a negative day count rather than as Today.
        val range = DateRange(TODAY_START + DAY / 2, Long.MAX_VALUE)
        assertEquals(DateRangeSummary.Today, summarizeDateRange(range, TODAY_START))
    }

    // ---- the shapes that have no source in the product ----

    @Test
    fun `a fully open range spans nothing rather than overflowing`() {
        val range = DateRange(Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(DateRangeSummary.Spanning(0), summarizeDateRange(range, TODAY_START))
    }

    @Test
    fun `a bounded range reports whole days`() {
        val range = DateRange(TODAY_START - 3 * DAY, TODAY_START)
        assertEquals(DateRangeSummary.Spanning(3), summarizeDateRange(range, TODAY_START))
    }

    @Test
    fun `no input produces a negative day count`() {
        // The property the overflow violated. Sweep every shape the app can build and assert the
        // answer is never a negative span -- which is what let "Today" match in the first place.
        val ranges = buildList {
            add(DateRange(Long.MIN_VALUE, Long.MAX_VALUE))
            add(DateRange(Long.MIN_VALUE, TODAY_START))
            add(DateRange(Long.MIN_VALUE, Long.MIN_VALUE + 1))
            for (daysBack in 0..400) add(preset(daysBack))
            add(DateRange(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        }
        for (range in ranges) {
            val summary = summarizeDateRange(range, TODAY_START)
            if (summary is DateRangeSummary.Spanning) {
                assertEquals(true, summary.days >= 0, "negative span for $range")
            }
        }
    }
}
