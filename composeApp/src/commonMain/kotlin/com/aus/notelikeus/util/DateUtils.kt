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

    fun isToday(timestamp: Long): Boolean
    fun formatDateTime(timestamp: Long, showYear: Boolean = true): String
    fun formatTime(timestamp: Long): String
    fun getTomorrowMorning(): Long
    fun getNextWeek(): Long
    fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long
    val DAY_IN_MILLIS: Long
}
