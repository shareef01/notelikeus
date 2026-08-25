package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L

/** 2026-08-20T00:00Z as the "today" every test is relative to (epoch day 20685). */
private const val TODAY_START = 1_787_184_000_000L

private val dayStart: (Int) -> Long = { offset -> TODAY_START + offset * DAY }

private fun parse(input: String) = NoteQueryParser.parse(input, dayStart)

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
        // 2026-08-19 is the day before the fixed "today", so it must equal yesterday exactly --
        // which is the point of routing ISO dates through the same dayStart function.
        assertEquals(parse("after:yesterday").after, parse("after:2026-08-19").after)
        assertEquals(TODAY_START + DAY, parse("before:2026-08-21").before)
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

class EpochDayTest {

    @Test
    fun `epoch day zero is the epoch`() {
        assertEquals(0L, epochDayFromCivil(1970, 1, 1))
    }

    @Test
    fun `known dates convert correctly`() {
        assertEquals(1L, epochDayFromCivil(1970, 1, 2))
        assertEquals(-1L, epochDayFromCivil(1969, 12, 31))
        assertEquals(19_723L, epochDayFromCivil(2024, 1, 1))
        // A leap day, which is where naive implementations go wrong.
        assertEquals(19_782L, epochDayFromCivil(2024, 2, 29))
        assertEquals(19_783L, epochDayFromCivil(2024, 3, 1))
    }

    @Test
    fun `consecutive days differ by one across a century boundary`() {
        // 1900 was not a leap year and 2000 was; both rules live in the same expression.
        assertEquals(
            epochDayFromCivil(1900, 3, 1) - epochDayFromCivil(1900, 2, 28),
            1L
        )
        assertEquals(
            epochDayFromCivil(2000, 3, 1) - epochDayFromCivil(2000, 2, 29),
            1L
        )
    }
}
