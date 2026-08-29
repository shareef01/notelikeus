package com.aus.notelikeus.util

import android.text.format.DateUtils as AndroidDateUtils
import java.text.DateFormat as JavaDateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual object DateUtils {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    /**
     * Calendar rather than arithmetic on [DAY_IN_MILLIS]: days are not all 86,400,000ms long, and
     * dividing by that lands an hour out on either side of a daylight-saving change.
     */
    actual fun startOfDay(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * The date is validated by day-of-month range, not by a non-lenient Calendar.
     *
     * Both halves of that matter. A *lenient* Calendar rolls `2026-02-31` forward to March 3 and
     * returns a plausible instant for a date the user cannot have meant, so the range check is what
     * makes an impossible date return null. But switching the Calendar to non-lenient to get that
     * check for free also makes it throw for a date whose local midnight does not *exist* — zones
     * that shift DST at midnight (America/Santiago, America/Havana) have no 00:00 on transition day
     * — and `before:2026-09-06` then came back null for everyone in them. Those are real dates and
     * must resolve.
     *
     * So: reject the impossible date explicitly, then let leniency do what it is good at and move a
     * missing midnight forward to the first instant that exists. That is also exactly what the
     * [startOfDay] overload above does on the same day, which keeps the two from disagreeing.
     */
    actual fun startOfDay(year: Int, month: Int, day: Int): Long? {
        if (month !in 1..12 || day < 1) return null
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month - 1)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        if (day > cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)) return null
        cal.set(java.util.Calendar.DAY_OF_MONTH, day)
        return cal.timeInMillis
    }

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
