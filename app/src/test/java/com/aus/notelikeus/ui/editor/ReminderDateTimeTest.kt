package com.aus.notelikeus.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ReminderDateTimeTest {

    @Test
    fun combineDateAndTime_usesLocalCalendarDay() {
        val dateCalendar = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 8, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val combined = combineDateAndTime(dateCalendar.timeInMillis, 14, 30)

        val result = Calendar.getInstance().apply { timeInMillis = combined }
        assertEquals(2026, result.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, result.get(Calendar.MONTH))
        assertEquals(8, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, result.get(Calendar.MINUTE))
    }
}
