package com.aus.notelikeus.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These exist because [DateUtils.formatTime] used to throw on every call.
 *
 * It passed `null` as the Context to android.text.format.DateUtils.formatDateTime with
 * FORMAT_SHOW_TIME — a flag the platform resolves via DateFormat.is24HourFormat(context), which
 * dereferences that null. EditorBottomBar formats the note timestamp unconditionally, so opening
 * any note crashed the app with a NullPointerException.
 *
 * Nothing caught it: these are plain functions with no Context dependency of their own, and no
 * test called them. Simply calling them is the whole test — an exception is the regression.
 */
class DateUtilsAndroidTest {

    private val sampleTimestamp = 1_700_000_000_000L

    @Test
    fun `formatTime returns a non-empty string instead of throwing`() {
        val formatted = DateUtils.formatTime(sampleTimestamp)
        assertTrue("formatTime produced nothing", formatted.isNotBlank())
    }

    @Test
    fun `formatDateTime returns a non-empty string with and without the year`() {
        assertTrue(DateUtils.formatDateTime(sampleTimestamp, showYear = false).isNotBlank())
        val withYear = DateUtils.formatDateTime(sampleTimestamp, showYear = true)
        assertTrue("expected a year in $withYear", withYear.contains("20"))
    }

    // isToday is deliberately not covered here: it delegates to android.text.format.DateUtils,
    // which is a stub returning defaults under plain JVM unit tests, so any assertion would be
    // testing the stub rather than the app. It needs Robolectric or an instrumented test.

    @Test
    fun `relative helpers return future timestamps`() {
        val now = DateUtils.currentTimeMillis()
        assertTrue(DateUtils.getTomorrowMorning() > now)
        assertTrue(DateUtils.getNextWeek() > now)
    }
}
