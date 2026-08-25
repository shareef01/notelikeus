package com.aus.notelikeus.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * Combining a date with a time of day has to land on the day the user picked, in their zone.
 *
 * Easy to get wrong by doing arithmetic on the epoch millis instead of going through a calendar:
 * any approach that adds `hour * 3600_000` drifts across a DST boundary and lands a reminder an
 * hour out, or on the wrong day entirely near midnight.
 *
 * This used to test a one-line wrapper in the editor that nothing else called, so it exercised the
 * indirection rather than the behaviour. The wrapper is gone; this tests the function itself.
 */
class DateUtilsCombineTest {

    @Test
    fun combineDateAndTime_usesLocalCalendarDay() {
        val dateCalendar = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 8, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val combined = DateUtils.combineDateAndTime(dateCalendar.timeInMillis, 14, 30)

        val result = Calendar.getInstance().apply { timeInMillis = combined }
        assertEquals(2026, result.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, result.get(Calendar.MONTH))
        assertEquals(8, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, result.get(Calendar.MINUTE))
    }

    /** Midnight is where an off-by-one-day bug would show up first. */
    @Test
    fun combineDateAndTime_keepsTheDayAtMidnight() {
        val dateCalendar = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 31, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val combined = DateUtils.combineDateAndTime(dateCalendar.timeInMillis, 0, 0)

        val result = Calendar.getInstance().apply { timeInMillis = combined }
        assertEquals(2026, result.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, result.get(Calendar.MONTH))
        assertEquals(31, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }
}
