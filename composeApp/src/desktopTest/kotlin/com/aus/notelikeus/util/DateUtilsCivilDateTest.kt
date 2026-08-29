package com.aus.notelikeus.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `DateUtils.startOfDay(year, month, day)` against real timezones.
 *
 * This is the half of the ISO-date fix that the parser cannot cover. `NoteQueryParserTest` proves
 * the parser delegates and does no arithmetic of its own; only a test that sets a real default
 * timezone and runs the real `Calendar` code can prove the answer it delegates *to* is right.
 *
 * The zones are chosen for what they discriminate. The original defect recovered a day index by
 * dividing local midnight by 86,400,000 — correct at or west of UTC, a day early east of it — so a
 * test that only ran in UTC (or on a machine set to the Americas) passed straight through it.
 */
class DateUtilsCivilDateTest {

    private val original: TimeZone = TimeZone.getDefault()

    @AfterTest
    fun restoreTimeZone() {
        TimeZone.setDefault(original)
    }

    private fun withZone(id: String, block: () -> Unit) {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        block()
    }

    /** What the answer must be: midnight on that civil date, read back through the same zone. */
    private fun assertIsLocalMidnightOf(year: Int, month: Int, day: Int, actual: Long?) {
        val cal = Calendar.getInstance().apply { timeInMillis = requireNotNull(actual) }
        assertEquals(year, cal.get(Calendar.YEAR))
        assertEquals(month, cal.get(Calendar.MONTH) + 1)
        assertEquals(day, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `resolves to that date's local midnight east of UTC`() {
        for (zone in listOf("Asia/Kolkata", "Asia/Tokyo", "Europe/Berlin", "Pacific/Auckland")) {
            withZone(zone) {
                assertIsLocalMidnightOf(2026, 8, 20, DateUtils.startOfDay(2026, 8, 20))
            }
        }
    }

    @Test
    fun `resolves to that date's local midnight at and west of UTC`() {
        for (zone in listOf("UTC", "America/New_York", "Pacific/Honolulu")) {
            withZone(zone) {
                assertIsLocalMidnightOf(2026, 8, 20, DateUtils.startOfDay(2026, 8, 20))
            }
        }
    }

    @Test
    fun `agrees with the timestamp overload for the same day`() {
        // The two overloads are the two ways the search operators reach a day boundary --
        // `before:2026-08-20` and `before:today` -- so they must not be able to disagree.
        withZone("Asia/Kolkata") {
            val noonThatDay = requireNotNull(DateUtils.startOfDay(2026, 8, 20)) + 12 * 60 * 60 * 1000L
            assertEquals(DateUtils.startOfDay(noonThatDay), DateUtils.startOfDay(2026, 8, 20))
        }
    }

    @Test
    fun `a date that does not exist returns null`() {
        withZone("Asia/Kolkata") {
            assertNull(DateUtils.startOfDay(2026, 2, 31))
            assertNull(DateUtils.startOfDay(2026, 2, 30))
            // 2026 is not a leap year; 2024 is, so the same day number differs by year.
            assertNull(DateUtils.startOfDay(2026, 2, 29))
            assertIsLocalMidnightOf(2024, 2, 29, DateUtils.startOfDay(2024, 2, 29))
        }
    }

    @Test
    fun `every real date of a year resolves onto itself, in zones that shift DST at midnight`() {
        // Some zones move the clock at midnight, so 00:00 has no instant at all on transition day.
        // The first attempt at this function used a non-lenient Calendar to reject `2026-02-31`,
        // and that throws here too — which silently turned `before:<transition date>` into an
        // unrecognised operator for everyone in the zone. Caught by this test, not by review.
        //
        // A whole year swept rather than a named date: tzdata moves transitions, and a test pinned
        // to one date stops testing anything the moment it does.
        for (zone in listOf("America/Santiago", "America/Havana", "Australia/Lord_Howe", "Asia/Beirut")) {
            withZone(zone) {
                for (month in 1..12) {
                    for (day in 1..daysIn(2026, month)) {
                        val where = "$zone 2026-$month-$day"
                        val start = assertNotNull(DateUtils.startOfDay(2026, month, day), where)
                        val cal = Calendar.getInstance().apply { timeInMillis = start }
                        assertEquals(day, cal.get(Calendar.DAY_OF_MONTH), where)
                        assertEquals(month, cal.get(Calendar.MONTH) + 1, where)
                    }
                }
            }
        }
    }

    private fun daysIn(year: Int, month: Int): Int = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)
}
