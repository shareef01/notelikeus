package com.aus.notelikeus.ui.editor

import java.util.Calendar

internal fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long {
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
