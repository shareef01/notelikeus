package com.aus.notelikeus.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar

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
        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        if (day > cal.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        cal.set(Calendar.DAY_OF_MONTH, day)
        return cal.timeInMillis
    }

    actual fun isToday(timestamp: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
               now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }

    actual fun formatDateTime(timestamp: Long, showYear: Boolean): String {
        val pattern = if (showYear) "MMM d, yyyy" else "MMM d"
        return SimpleDateFormat(pattern).format(Date(timestamp))
    }

    actual fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a").format(Date(timestamp))
    }

    actual fun getTomorrowMorning(): Long {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    actual fun getNextWeek(): Long {
        val cal = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    actual fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long {
        val dateCalendar = Calendar.getInstance().apply { timeInMillis = dateMillis }
        return Calendar.getInstance().apply {
            set(
                dateCalendar.get(Calendar.YEAR),
                dateCalendar.get(Calendar.MONTH),
                dateCalendar.get(Calendar.DAY_OF_MONTH),
                hour,
                minute,
                0
            )
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    actual val DAY_IN_MILLIS: Long = 24 * 60 * 60 * 1000L
}
