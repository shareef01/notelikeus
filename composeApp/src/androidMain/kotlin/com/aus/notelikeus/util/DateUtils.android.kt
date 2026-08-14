package com.aus.notelikeus.util

import android.text.format.DateUtils as AndroidDateUtils
import java.text.DateFormat as JavaDateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual object DateUtils {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    actual fun isToday(timestamp: Long): Boolean {
        return AndroidDateUtils.isToday(timestamp)
    }

    /**
     * Formatted with java.text rather than [AndroidDateUtils], deliberately.
     *
     * These are called from commonMain, which has no Context to pass. The previous code passed
     * `null` on the assumption that "Context is only needed for some flags" — but FORMAT_SHOW_TIME
     * is one of the flags that needs it: the platform resolves 12- vs 24-hour by calling
     * DateFormat.is24HourFormat(context), which dereferences the null and throws. That made
     * [formatTime] crash every time, and since EditorBottomBar formats the note's timestamp
     * unconditionally, opening any note took the whole app down with it.
     *
     * java.text picks its pattern from the default locale instead, needs no Context, and matches
     * what the desktop implementation already does.
     */
    actual fun formatDateTime(timestamp: Long, showYear: Boolean): String {
        val pattern = if (showYear) "MMM d, yyyy" else "MMM d"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    }

    actual fun formatTime(timestamp: Long): String {
        return JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT, Locale.getDefault())
            .format(Date(timestamp))
    }

    actual fun getTomorrowMorning(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    actual fun getNextWeek(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.WEEK_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    actual fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long {
        val dateCalendar = java.util.Calendar.getInstance().apply { timeInMillis = dateMillis }
        return java.util.Calendar.getInstance().apply {
            set(
                dateCalendar.get(java.util.Calendar.YEAR),
                dateCalendar.get(java.util.Calendar.MONTH),
                dateCalendar.get(java.util.Calendar.DAY_OF_MONTH),
                hour,
                minute,
                0
            )
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    actual val DAY_IN_MILLIS: Long = 24 * 60 * 60 * 1000L
}
