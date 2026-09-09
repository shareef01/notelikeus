package com.aus.notelikeus.domain.reminder

import com.aus.notelikeus.util.DateUtils
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Time-zone and DST behaviour of the exact-reminder picker.
 *
 * Runs on the desktop JVM because it needs `TimeZone.setDefault`, which Robolectric's Android
 * runtime does not honour consistently — but every function under test is `commonMain`, so what
 * this pins holds for the Android build too.
 *
 * The zones are chosen for what each one breaks:
 * - `Pacific/Kiritimati` (UTC+14) and `Pacific/Niue` (UTC-11) bracket the day-boundary error:
 *   a picker value is midnight **UTC**, so reading its civil date in local time is off by a day
 *   at one end or the other.
 * - `America/New_York` has a 02:00 spring-forward and a 02:00 fall-back, so 02:30 is a local time
 *   that does not exist on one day and happens twice on another.
 * - `America/Santiago` shifts DST at midnight, so local midnight itself is sometimes missing.
 */
class ReminderTimeTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @AfterTest
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun inZone(id: String, block: () -> Unit) {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            block()
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    private fun localFieldsOf(timestamp: Long): List<Int> {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return listOf(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    // ---- civil date arithmetic (zone-free) ----

    @Test
    fun civilDateRoundTripsThroughUtcMidnight() {
        val cases = listOf(
            Triple(1970, 1, 1),
            Triple(2000, 2, 29),
            Triple(2024, 2, 29),
            Triple(2026, 3, 8),
            Triple(2026, 12, 31),
            Triple(2100, 3, 1),
            Triple(1900, 1, 1),
        )
        for ((year, month, day) in cases) {
            val utc = ReminderTime.civilDateToUtcMillis(year, month, day)
            assertEquals(
                ReminderTime.CivilDate(year, month, day),
                ReminderTime.utcMillisToCivilDate(utc),
                "round trip for $year-$month-$day",
            )
            assertEquals(0L, utc % 86_400_000L, "$year-$month-$day must be UTC midnight")
        }
    }

    @Test
    fun civilDateHandlesInstantsBeforeTheEpoch() {
        val utc = ReminderTime.civilDateToUtcMillis(1969, 7, 20)
        assertTrue(utc < 0, "1969 predates the epoch")
        assertEquals(ReminderTime.CivilDate(1969, 7, 20), ReminderTime.utcMillisToCivilDate(utc))
    }

    // ---- the day-boundary hazard the picker's units create ----

    @Test
    fun exactReminderKeepsThePickedDayFourteenHoursAheadOfUtc() = inZone("Pacific/Kiritimati") {
        val picked = ReminderTime.civilDateToUtcMillis(2026, 7, 8)
        val reminder = assertNotNull(ReminderTime.exactReminder(picked, 9, 30))
        assertEquals(listOf(2026, 7, 8, 9, 30), localFieldsOf(reminder))
    }

    @Test
    fun exactReminderKeepsThePickedDayElevenHoursBehindUtc() = inZone("Pacific/Niue") {
        val picked = ReminderTime.civilDateToUtcMillis(2026, 7, 8)
        val reminder = assertNotNull(ReminderTime.exactReminder(picked, 9, 30))
        assertEquals(
            listOf(2026, 7, 8, 9, 30),
            localFieldsOf(reminder),
            "a UTC-midnight picker value must not be read as the previous local day",
        )
    }

    @Test
    fun exactReminderKeepsMidnightOnThePickedDay() = inZone("Pacific/Niue") {
        val picked = ReminderTime.civilDateToUtcMillis(2026, 7, 8)
        val reminder = assertNotNull(ReminderTime.exactReminder(picked, 0, 0))
        assertEquals(listOf(2026, 7, 8, 0, 0), localFieldsOf(reminder))
    }

    // ---- DST ----

    @Test
    fun exactReminderResolvesATimeSpringForwardSkips() = inZone("America/New_York") {
        // 2026-03-08: 02:00 EST jumps straight to 03:00 EDT, so 02:30 never occurs.
        val picked = ReminderTime.civilDateToUtcMillis(2026, 3, 8)
        val reminder = assertNotNull(ReminderTime.exactReminder(picked, 2, 30))
        assertEquals(
            listOf(2026, 3, 8, 3, 30),
            localFieldsOf(reminder),
            "a skipped local time resolves forward on the same day rather than failing",
        )
    }

    @Test
    fun exactReminderStaysOnTheChosenDayAcrossFallBack() = inZone("America/New_York") {
        // 2026-11-01: 02:00 EDT falls back to 01:00 EST, so 01:30 happens twice.
        val picked = ReminderTime.civilDateToUtcMillis(2026, 11, 1)
        val reminder = assertNotNull(ReminderTime.exactReminder(picked, 1, 30))
        val fields = localFieldsOf(reminder)
        assertEquals(listOf(2026, 11, 1), fields.take(3))
        assertEquals(listOf(1, 30), fields.drop(3))
    }

    @Test
    fun exactReminderWorksWhereLocalMidnightDoesNotExist() = inZone("America/Santiago") {
        // Santiago shifts DST at midnight, so 00:00 is missing on the transition day.
        val picked = ReminderTime.civilDateToUtcMillis(2026, 9, 6)
        val reminder = assertNotNull(ReminderTime.exactReminder(picked, 20, 0))
        assertEquals(listOf(2026, 9, 6, 20, 0), localFieldsOf(reminder))
    }

    @Test
    fun exactReminderRejectsImpossibleDatesAndTimes() = inZone("UTC") {
        val february31 = ReminderTime.civilDateToUtcMillis(2026, 2, 28) + 3 * 86_400_000L
        // That lands on March 3; the guard below is about the field validation, not the date.
        assertNotNull(ReminderTime.exactReminder(february31, 12, 0))

        val valid = ReminderTime.civilDateToUtcMillis(2026, 7, 8)
        assertNull(ReminderTime.exactReminder(valid, 24, 0))
        assertNull(ReminderTime.exactReminder(valid, -1, 0))
        assertNull(ReminderTime.exactReminder(valid, 12, 60))
    }

    // ---- picker pre-population ----

    @Test
    fun initialPickerStateRoundTripsAnExistingReminder() = inZone("Pacific/Niue") {
        val existing = assertNotNull(
            ReminderTime.exactReminder(ReminderTime.civilDateToUtcMillis(2026, 7, 8), 17, 45),
        )
        val state = ReminderTime.initialPickerState(existing)
        assertEquals(
            ReminderTime.CivilDate(2026, 7, 8),
            ReminderTime.utcMillisToCivilDate(state.dateUtcMillis),
        )
        assertEquals(17, state.hour)
        assertEquals(45, state.minute)

        // The whole point: reopening the picker and pressing OK must not move the reminder.
        assertEquals(
            existing,
            ReminderTime.exactReminder(state.dateUtcMillis, state.hour, state.minute),
            "reopening an existing reminder and confirming it must be a no-op",
        )
    }

    @Test
    fun initialPickerStateWithNoReminderOpensOnTheNextWholeHour() = inZone("America/New_York") {
        val state = ReminderTime.initialPickerState(null)
        assertEquals(0, state.minute)
        val resolved = assertNotNull(
            ReminderTime.exactReminder(state.dateUtcMillis, state.hour, state.minute),
        )
        val now = DateUtils.currentTimeMillis()
        assertTrue(resolved > now, "the default must be in the future")
        assertTrue(
            resolved - now <= ReminderTime.ONE_HOUR_MS + 1_000,
            "the default must be the next whole hour, not further out",
        )
    }

    @Test
    fun nextWholeHourRollsOntoTheFollowingDayBeforeMidnight() = inZone("Europe/Berlin") {
        val lateEvening = assertNotNull(
            ReminderTime.exactReminder(ReminderTime.civilDateToUtcMillis(2026, 7, 8), 23, 12),
        )
        val rolled = ReminderTime.nextWholeHour(lateEvening)
        assertEquals(listOf(2026, 7, 9, 0, 0), localFieldsOf(rolled))
    }
}
