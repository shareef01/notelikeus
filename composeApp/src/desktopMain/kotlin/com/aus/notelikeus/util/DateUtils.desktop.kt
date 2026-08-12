package com.aus.notelikeus.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar

actual object DateUtils {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

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
