package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L

/** Epoch day of 2026-08-20, the "today" every test is relative to. */
private const val TODAY_EPOCH_DAY = 20_685L

/**
 * Days since 1970-01-01 for a civil date, by Howard Hinnant's `days_from_civil`.
 *
 * Fixture arithmetic, not production arithmetic. The parser used to carry a copy of this and use
 * it to turn a typed date into a day offset; that is the calculation the off-by-one lived in, and
 * it is gone. The tests still need to name a date, so the helper lives here now — where getting it
 * wrong fails a test rather than shipping.
 */
private fun epochDay(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097 + doe - 719_468
}

/**
 * A timezone, modelled as a fixed offset from UTC.
 *
 * Both injected functions answer from the same clock, which is what the platform implementations
 * do — and modelling them as one object is what makes it impossible to write a fixture where
 * `after:yesterday` and `after:<yesterday's date>` disagree without the test saying so.
 */
private class Zone(offsetHours: Double) {
    private val offset = (offsetHours * 60 * 60 * 1000).toLong()

    /** Local midnight of the day [daysFromToday] from today. */
    val dayStart: (Int) -> Long = { n -> (TODAY_EPOCH_DAY + n) * DAY - offset }

    /** Local midnight of a civil date; null for a date that does not exist. */
    val dayStartOfDate: (Int, Int, Int) -> Long? = { y, m, d ->
        if (m == 2 && d > 29) null else epochDay(y, m, d) * DAY - offset
    }

    fun midnightOf(year: Int, month: Int, day: Int): Long = epochDay(year, month, day) * DAY - offset
}

private val UTC = Zone(0.0)

/** UTC+05:30 — the shape of timezone the old day-index arithmetic resolved a day late in. */
private val KOLKATA = Zone(5.5)

private val TODAY_START = UTC.dayStart(0)

private fun parse(input: String, zone: Zone = UTC) =
    NoteQueryParser.parse(input, zone.dayStart, zone.dayStartOfDate)

class NoteQueryParserTest {

    // ---- free text is the default, and stays the default ----

    @Test
    fun `plain text is left alone`() {
        assertEquals("milk and bread", parse("milk and bread").text)
    }

    @Test
    fun `a colon in ordinary text is not an operator`() {
        // The property most easily broken here: "TODO:" and "3:1" must search, not vanish.
        assertEquals("TODO: fix the ratio 3:1", parse("TODO: fix the ratio 3:1").text)
        assertTrue(parse("TODO: fix").labelNames.isEmpty())
    }

    @Test
    fun `empty input parses to an empty query`() {
        val q = parse("")
        assertEquals("", q.text)
        assertTrue(q.labelNames.isEmpty() && q.flags.isEmpty() && q.unknown.isEmpty())
    }

    // ---- label and colour ----

    @Test
    fun `label and colour are captured as names, not resolved`() {
        val q = parse("label:sec color:green")
        assertEquals(listOf("sec"), q.labelNames)
        assertEquals(listOf("green"), q.colorNames)
        assertEquals("", q.text)
    }

    @Test
    fun `label keeps its case but colour does not`() {
        // A label is user data and matched against user data; a colour name is a keyword.
        val q = parse("label:Work color:GREEN")
        assertEquals(listOf("Work"), q.labelNames)
        assertEquals(listOf("green"), q.colorNames)
    }

    @Test
    fun `quoted values keep their spaces`() {
        val q = parse("""label:"next actions" milk""")
        assertEquals(listOf("next actions"), q.labelNames)
        assertEquals("milk", q.text)
    }

    @Test
    fun `repeated operators accumulate`() {
        val q = parse("label:a label:b")
        assertEquals(listOf("a", "b"), q.labelNames)
    }

    @Test
    fun `colour accepts both spellings`() {
        assertEquals(listOf("teal"), parse("colour:teal").colorNames)
    }

    // ---- flags and scope ----

    @Test
    fun `is maps to flags and to scope`() {
        assertEquals(setOf(NoteFlag.PINNED), parse("is:pinned").flags)
        assertEquals(setOf(NoteFlag.UNTITLED), parse("is:untitled").flags)
        assertEquals(setOf(NoteFlag.REMINDER_OVERDUE), parse("is:overdue").flags)
        assertEquals(NoteScope.ARCHIVE, parse("is:archived").scope)
        assertEquals(NoteScope.TRASH, parse("is:trashed").scope)
    }

    @Test
    fun `has maps to flags`() {
        assertEquals(setOf(NoteFlag.HAS_REMINDER), parse("has:reminder").flags)
        assertEquals(setOf(NoteFlag.HAS_CHECKLIST), parse("has:checklist").flags)
        assertEquals(setOf(NoteFlag.HAS_UNCHECKED_ITEMS), parse("has:unchecked").flags)
        assertEquals(setOf(NoteFlag.HAS_LINKS), parse("has:links").flags)
    }

    @Test
    fun `in selects a scope`() {
        assertEquals(NoteScope.TRASH, parse("in:trash").scope)
        assertEquals(NoteScope.ARCHIVE, parse("in:archive").scope)
        assertEquals(NoteScope.ALL, parse("in:all").scope)
        assertEquals(NoteScope.ACTIVE, parse("in:notes").scope)
    }

    @Test
    fun `both British and American spellings of unlabelled work`() {
        assertEquals(setOf(NoteFlag.UNLABELED), parse("is:unlabeled").flags)
        assertEquals(setOf(NoteFlag.UNLABELED), parse("is:unlabelled").flags)
    }

    // ---- dates ----

    @Test
    fun `relative dates resolve through the injected day start`() {
        assertEquals(TODAY_START, parse("after:today").after)
        assertEquals(TODAY_START - DAY, parse("after:yesterday").after)
        assertEquals(TODAY_START - 7 * DAY, parse("after:week").after)
        assertEquals(TODAY_START, parse("before:today").before)
    }

    @Test
    fun `an ISO date lands on the same midnight as a relative one`() {
        // 2026-08-19 is the day before the fixed "today", so it must equal yesterday exactly.
        assertEquals(parse("after:yesterday").after, parse("after:2026-08-19").after)
        assertEquals(TODAY_START + DAY, parse("before:2026-08-21").before)
    }

    @Test
    fun `an ISO date lands on that date east of UTC too`() {
        // The regression guard. Local midnight in a positive-offset zone falls on the *previous*
        // UTC day, so the old implementation -- which recovered a day index by dividing
        // dayStart(0) by 86,400,000 -- resolved every typed date one day late here while staying
        // correct in UTC and in the Americas. A fixture pinned to UTC cannot tell the two apart,
        // which is why this asserts against a zone that is not UTC.
        assertEquals(
            KOLKATA.midnightOf(2026, 8, 20),
            parse("before:2026-08-20", KOLKATA).before
        )
        assertEquals(
            parse("after:yesterday", KOLKATA).after,
            parse("after:2026-08-19", KOLKATA).after
        )
    }

    @Test
    fun `a date that does not exist is unknown rather than rolled forward`() {
        // February 31 used to be accepted: the day-of-month check only bounded it at 31, and the
        // arithmetic below it happily carried the overflow into March.
        val q = parse("before:2026-02-31")
        assertNull(q.before)
        assertEquals(listOf("before:2026-02-31"), q.unknown)
    }

    @Test
    fun `a date operator sets the date field it applies to`() {
        assertEquals(DateField.EDITED, parse("after:today").dateField)
        assertNull(parse("milk").dateField)
    }

    // ---- malformed input ----

    @Test
    fun `a bare operator is recorded as unknown rather than searched for`() {
        // Someone mid-thought typed "label:". Searching for the literal text is never the intent.
        val q = parse("label:")
        assertEquals(listOf("label:"), q.unknown)
        assertEquals("", q.text)
        assertTrue(q.labelNames.isEmpty())
    }

    @Test
    fun `an unrecognised value is unknown, not silently ignored`() {
        val q = parse("is:sideways")
        assertEquals(listOf("is:sideways"), q.unknown)
        assertTrue(q.flags.isEmpty())
    }

    @Test
    fun `a malformed date is unknown rather than a wrong date`() {
        listOf("before:2026-13-01", "before:2026-08-99", "after:not-a-date", "before:2026-08").forEach {
            val q = parse(it)
            assertNull(q.before, "$it should not have produced a bound")
            assertNull(q.after, "$it should not have produced a bound")
            assertEquals(listOf(it), q.unknown, it)
        }
    }

    @Test
    fun `an unknown prefix stays free text`() {
        // "labe:work" is a typo, not an operator -- searching for it is the honest fallback and
        // lets the user see what they typed.
        assertEquals("labe:work", parse("labe:work").text)
    }

    @Test
    fun `a leading colon is free text`() {
        assertEquals(":pinned", parse(":pinned").text)
    }

    // ---- mixtures ----

    @Test
    fun `operators and free text coexist in any order`() {
        val q = parse("milk label:shopping is:pinned bread has:checklist")
        assertEquals("milk bread", q.text)
        assertEquals(listOf("shopping"), q.labelNames)
        assertEquals(setOf(NoteFlag.PINNED, NoteFlag.HAS_CHECKLIST), q.flags)
    }

    @Test
    fun `everything at once`() {
        val q = parse("""budget label:"Q3 plan" color:blue is:pinned has:reminder in:archive after:2026-08-19""")
        assertEquals("budget", q.text)
        assertEquals(listOf("Q3 plan"), q.labelNames)
        assertEquals(listOf("blue"), q.colorNames)
        assertEquals(setOf(NoteFlag.PINNED, NoteFlag.HAS_REMINDER), q.flags)
        assertEquals(NoteScope.ARCHIVE, q.scope)
        assertEquals(TODAY_START - DAY, q.after)
    }
}
