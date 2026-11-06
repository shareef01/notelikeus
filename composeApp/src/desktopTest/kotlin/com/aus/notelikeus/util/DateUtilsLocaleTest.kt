package com.aus.notelikeus.util

import com.aus.notelikeus.domain.reminder.ReminderTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DateUtilsLocaleTest {
    @Test
    fun desktopClockRespectsTwentyFourHourLocale() {
        val originalLocale = Locale.getDefault()
        val originalZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val instant = LocalDate.of(2026, 7, 8).atTime(13, 5).atZone(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
            assertEquals("13:05", DateUtils.formatTime(instant))
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun isoDatesAndReminderPickerStayGregorianUnderNonGregorianLocales() {
        val originalLocale = Locale.getDefault()
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))
            val expected = LocalDate.of(2026, 7, 8).atStartOfDay(ZoneId.of("Asia/Bangkok"))
                .toInstant().toEpochMilli()
            for (tag in listOf("th-TH", "ja-JP-u-ca-japanese", "en-US-u-ca-buddhist")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(expected, DateUtils.startOfDay(2026, 7, 8), tag)
                val atNine = expected + 9 * ReminderTime.ONE_HOUR_MS
                assertEquals(LocalDateTimeFields(2026, 7, 8, 9, 0), DateUtils.localDateTimeFields(atNine), tag)
                val picker = ReminderTime.initialPickerState(atNine)
                assertEquals(ReminderTime.civilDateToUtcMillis(2026, 7, 8), picker.dateUtcMillis, tag)
                assertEquals(atNine, ReminderTime.exactReminder(picker.dateUtcMillis, picker.hour, picker.minute), tag)
            }
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }
}
