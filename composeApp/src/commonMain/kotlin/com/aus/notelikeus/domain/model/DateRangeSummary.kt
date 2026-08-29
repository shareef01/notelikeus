package com.aus.notelikeus.domain.model

private const val DAY_MILLIS = 86_400_000L

/**
 * What a [DateRange] means, independent of how it is worded.
 *
 * Separated from the composable that renders it because the previous version was a `@Composable`
 * calling `stringResource`, which is untestable without a Compose harness — and it was wrong in
 * *every* case it could actually encounter, for years, with nothing to notice.
 */
sealed interface DateRangeSummary {
    /** A window ending now: today, the last week, the last month. */
    object Today : DateRangeSummary
    object Last7Days : DateRangeSummary
    object Last30Days : DateRangeSummary

    /** `before:2026-08-01` — open below, so only the upper bound means anything. */
    data class Before(val toExclusive: Long) : DateRangeSummary

    /** `after:2026-08-01` — open above, and further back than any preset. */
    data class Since(val fromInclusive: Long) : DateRangeSummary

    /** Bounded on both sides, which only a future date picker would produce. */
    data class Spanning(val days: Long) : DateRangeSummary
}

/**
 * Summarises [range] relative to [todayStart] (local midnight today).
 *
 * **Never subtract the bounds.** Both are sentinels in the ordinary case — `Long.MIN_VALUE` when
 * the user typed `before:`, `Long.MAX_VALUE` for `after:` *and* for every preset in `DatePreset`,
 * which pins its upper bound there on purpose so a note edited a moment ago stays included.
 *
 * The previous implementation computed `toExclusive - fromInclusive` and bucketed the result,
 * which overflows signed 64-bit arithmetic in both directions:
 *
 * - `before:X` gave `X - Long.MIN_VALUE`, which wraps **negative**, so the `<= 1 day` bucket
 *   matched and every such filter was labelled **"Today"**.
 * - `after:Y` and all three presets gave `Long.MAX_VALUE - Y` — about 9.2e18 ms — which fell
 *   through to the raw-day-count branch and rendered **"106751970482 d"**, roughly 292 million
 *   years.
 *
 * Between them those two cases cover everything the app can produce, so the Today / Last 7 / Last
 * 30 branches were unreachable: a bounded range has no source in the product.
 */
fun summarizeDateRange(range: DateRange, todayStart: Long): DateRangeSummary {
    val openBelow = range.fromInclusive == Long.MIN_VALUE
    val openAbove = range.toExclusive == Long.MAX_VALUE

    if (openBelow && openAbove) return DateRangeSummary.Spanning(0)
    if (openBelow) return DateRangeSummary.Before(range.toExclusive)

    if (openAbove) {
        // How far back the window starts, in whole days from today's midnight. DatePreset builds
        // exactly this shape, so these three buckets are what make a preset chip say its own name.
        val daysBack = floorDiv(todayStart - range.fromInclusive, DAY_MILLIS)
        return when {
            daysBack <= 0L -> DateRangeSummary.Today
            daysBack <= 7L -> DateRangeSummary.Last7Days
            daysBack <= 30L -> DateRangeSummary.Last30Days
            else -> DateRangeSummary.Since(range.fromInclusive)
        }
    }

    return DateRangeSummary.Spanning(floorDiv(range.toExclusive - range.fromInclusive, DAY_MILLIS))
}

/** Floor division, which Kotlin's `/` is not for negative numerators. */
private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}
