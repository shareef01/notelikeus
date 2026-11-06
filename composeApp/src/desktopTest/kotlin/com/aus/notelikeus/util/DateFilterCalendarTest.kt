package com.aus.notelikeus.util

import com.aus.notelikeus.domain.model.DateRange
import com.aus.notelikeus.domain.model.DateRangeSummary
import com.aus.notelikeus.domain.model.summarizeDateRange
import com.aus.notelikeus.ui.main.components.DatePreset
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DateFilterCalendarTest {
    @Test
    fun combinedDateOperatorsCountCivilDatesAcrossDst() = withNewYork {
        val start = midnight(LocalDate.of(2026, 3, 1))
        val end = midnight(LocalDate.of(2026, 4, 1))
        assertEquals(DateRangeSummary.Spanning(31), summarizeDateRange(DateRange(start, end), end))
        assertEquals(DateRangeSummary.Spanning(0), summarizeDateRange(DateRange(end, start), end))
    }

    @Test
    fun presetsUseLocalMidnightAcrossBothDstTransitions() = withNewYork {
        for (day in listOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 11, 3))) {
            val today = midnight(day)
            assertEquals(midnight(day.minusDays(7)), DatePreset.LAST_7.range(today)?.fromInclusive)
            assertEquals(midnight(day.minusDays(30)), DatePreset.LAST_30.range(today)?.fromInclusive)
            assertEquals(today, DatePreset.TODAY.range(today)?.fromInclusive)
        }
    }

    @Test
    fun eightCalendarDaysDoNotBecomeLastSevenDaysAfterSpringForward() = withNewYork {
        val day = LocalDate.of(2026, 3, 10)
        val from = midnight(day.minusDays(8))
        assertEquals(
            DateRangeSummary.Last30Days,
            summarizeDateRange(DateRange(from, Long.MAX_VALUE), midnight(day)),
        )
    }

    @Test
    fun shiftingYesterdayReachesTodayWhenClocksFallBack() = withNewYork {
        val yesterday = LocalDate.of(2026, 11, 1).atTime(0, 30).atZone(ZoneId.of("America/New_York"))
        val shifted = DateUtils.shiftLocalDays(yesterday.toInstant().toEpochMilli(), 1)
        assertEquals(LocalDateTimeFields(2026, 11, 2, 0, 30), DateUtils.localDateTimeFields(shifted))
    }

    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    private fun withNewYork(block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
