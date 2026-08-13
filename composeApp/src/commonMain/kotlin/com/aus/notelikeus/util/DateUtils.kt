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

    fun isToday(timestamp: Long): Boolean
    fun formatDateTime(timestamp: Long, showYear: Boolean = true): String
    fun formatTime(timestamp: Long): String
    fun getTomorrowMorning(): Long
    fun getNextWeek(): Long
    fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long
    val DAY_IN_MILLIS: Long
}
