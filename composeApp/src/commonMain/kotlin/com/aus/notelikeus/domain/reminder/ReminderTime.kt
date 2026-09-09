package com.aus.notelikeus.domain.reminder

import com.aus.notelikeus.util.DateUtils

/**
 * The instants a reminder can be set to, and the conversions a date/time picker needs.
 *
 * This exists as a domain object rather than as arithmetic inside the dialog because the two
 * conversions below are the ones that go wrong quietly. Both are about the same hazard: a civil
 * date and an instant are different things, and every reminder bug this project has had came from
 * treating one as the other.
 */
object ReminderTime {

    /** One of the quick choices offered alongside picking an exact moment. */
    enum class Preset { IN_ONE_HOUR, TOMORROW_MORNING, NEXT_WEEK }

    const val ONE_HOUR_MS: Long = 60 * 60 * 1000L

    fun resolve(preset: Preset): Long = when (preset) {
        Preset.IN_ONE_HOUR -> DateUtils.currentTimeMillis() + ONE_HOUR_MS
        Preset.TOMORROW_MORNING -> DateUtils.getTomorrowMorning()
        Preset.NEXT_WEEK -> DateUtils.getNextWeek()
    }

    /**
     * Where the picker should open: the existing reminder, or the next whole hour.
     *
     * Returns the *date picker's* value in the units Material3 uses — UTC midnight — alongside the
     * local hour and minute, so the two halves of the dialog agree about which day is selected.
     */
    fun initialPickerState(existingReminder: Long?): PickerState {
        val anchor = existingReminder ?: nextWholeHour(DateUtils.currentTimeMillis())
        val fields = DateUtils.localDateTimeFields(anchor)
        return PickerState(
            dateUtcMillis = civilDateToUtcMillis(fields.year, fields.month, fields.day),
            hour = fields.hour,
            minute = fields.minute,
        )
    }

    /**
     * The instant a picked civil date and time of day names, in the user's own zone.
     *
     * [datePickerUtcMillis] is what Material3's `DatePickerState.selectedDateMillis` reports:
     * midnight **UTC** on the selected civil date. Handing that value straight to a local-calendar
     * conversion is a day-boundary bug waiting for the first user west of UTC — their
     * `2026-07-08T00:00Z` reads as the evening of July 7 locally, and the reminder lands a day
     * early. So the civil date is recovered from it in UTC (exact: the value is UTC midnight by
     * contract, no zone involved), and only then handed to the platform's local-midnight
     * conversion.
     *
     * Returns null for a date that does not exist. A local time that does not exist — the hour a
     * spring-forward DST change skips — resolves forward to the first instant that does, which is
     * what [DateUtils.combineDateAndTime] already does for typed search dates.
     */
    fun exactReminder(datePickerUtcMillis: Long, hour: Int, minute: Int): Long? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val civil = utcMillisToCivilDate(datePickerUtcMillis)
        val localMidnight = DateUtils.startOfDay(civil.year, civil.month, civil.day) ?: return null
        return DateUtils.combineDateAndTime(localMidnight, hour, minute)
    }

    /** The next whole hour after [now], the default a picker opens on with nothing set. */
    fun nextWholeHour(now: Long): Long {
        val fields = DateUtils.localDateTimeFields(now)
        val midnight = DateUtils.startOfDay(now)
        // hour + 1 rather than arithmetic on `now`: combineDateAndTime normalises 24 onto the next
        // day through the calendar, which is also what keeps this right across a DST change.
        return DateUtils.combineDateAndTime(midnight, fields.hour + 1, 0)
    }

    data class PickerState(val dateUtcMillis: Long, val hour: Int, val minute: Int)

    data class CivilDate(val year: Int, val month: Int, val day: Int)

    /**
     * The civil date of a UTC-midnight instant.
     *
     * Pure integer arithmetic on days since the epoch (Howard Hinnant's `civil_from_days`), so it
     * is exact for every date and involves no time zone at all — which is the point: the input is
     * defined in UTC and must not be re-interpreted anywhere else.
     */
    fun utcMillisToCivilDate(utcMillis: Long): CivilDate {
        val days = utcMillis.floorDiv(86_400_000L)
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return CivilDate(
            year = (if (m <= 2) y + 1 else y).toInt(),
            month = m.toInt(),
            day = d.toInt(),
        )
    }

    /** Inverse of [utcMillisToCivilDate]: midnight UTC on the given civil date. */
    fun civilDateToUtcMillis(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153L * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097L + doe - 719_468L) * 86_400_000L
    }
}
