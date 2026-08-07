package com.aus.notelikeus.util

import android.text.format.DateUtils as AndroidDateUtils
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable

actual object DateUtils {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    actual fun isToday(timestamp: Long): Boolean {
        return AndroidDateUtils.isToday(timestamp)
    }

    actual fun formatDateTime(timestamp: Long, showYear: Boolean): String {
        // This is a bit tricky without context, so we might need to pass it or use a default
        // But for commonMain usage, we often want a simple string.
        return AndroidDateUtils.formatDateTime(
            null, // Context is only needed for some flags
            timestamp,
            AndroidDateUtils.FORMAT_SHOW_DATE or 
            (if (showYear) AndroidDateUtils.FORMAT_SHOW_YEAR else 0) or 
            AndroidDateUtils.FORMAT_ABBREV_MONTH
        )
    }

    actual fun formatTime(timestamp: Long): String {
        return AndroidDateUtils.formatDateTime(
            null,
            timestamp,
            AndroidDateUtils.FORMAT_SHOW_TIME
        )
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
