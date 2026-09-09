package com.aus.notelikeus.util

expect object DateUtils {
    /**
     * Wall-clock "now" in epoch millis.
     *
     * commonMain must go through this rather than the JVM's `System.currentTimeMillis()`: that
     * resolves today only because every target happens to be JVM-based, and it would break the
     * moment a native, JS or wasm target is added.
     */
    fun currentTimeMillis(): Long

    /**
     * Epoch millis at local midnight of the day containing [timestamp].
     *
     * The search operators need a day boundary -- `after:yesterday` has to mean the same instant
     * as `after:2026-08-19` -- and midnight is a timezone question, so it is answered per platform
     * rather than in the parser.
     */
    fun startOfDay(timestamp: Long): Long

    /**
     * Epoch millis at local midnight of the civil date [year]-[month]-[day], or null if that date
     * does not exist (`2026-02-31`).
     *
     * A typed `before:2026-08-01` has to land on the same instant as `before:today` would on that
     * day, and only the platform knows where that midnight is. Deriving it in commonMain by
     * dividing [startOfDay]'s result back into a day index is what this replaces: local midnight
     * east of UTC falls on the previous UTC day, so the division answered a day early and every
     * ISO date resolved one day late.
     *
     * [month] is 1-based, unlike `Calendar.MONTH`.
     */
    fun startOfDay(year: Int, month: Int, day: Int): Long?

    /**
     * The local wall-clock fields of [timestamp].
     *
     * Needed to pre-populate a date/time picker from a reminder that is already set: the picker
     * speaks in civil date and time-of-day, and only the platform knows which ones a given instant
     * shows as. Deriving them in commonMain by dividing epoch millis is the same mistake
     * [startOfDay] documents — it answers in UTC, so every reminder east or west of it opens the
     * picker on the wrong day or hour.
     */
    fun localDateTimeFields(timestamp: Long): LocalDateTimeFields

    fun isToday(timestamp: Long): Boolean
    fun formatDateTime(timestamp: Long, showYear: Boolean = true): String
    fun formatTime(timestamp: Long): String
    fun getTomorrowMorning(): Long
    fun getNextWeek(): Long
    fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long
    val DAY_IN_MILLIS: Long
}

/**
 * A local wall-clock date and time of day, as a picker presents it.
 *
 * [month] is 1-based, matching [DateUtils.startOfDay] and unlike `Calendar.MONTH`.
 */
data class LocalDateTimeFields(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
)
