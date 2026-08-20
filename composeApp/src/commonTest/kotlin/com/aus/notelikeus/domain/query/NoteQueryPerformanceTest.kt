package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteSortOrder
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * The brief's budget: 5,000 notes filtered and searched in under 50ms.
 *
 * This is the measurement that decides whether the query system needs to push filtering into SQL.
 * If an in-memory pass over a precomputed folded column clears the budget comfortably, a
 * parameterised `RawQuery` and the Flow rewiring it needs are complexity bought for nothing.
 *
 * The budget is generous on purpose relative to what it is protecting against — this is a
 * regression guard, not a benchmark, and it runs on shared CI hardware. What would actually breach
 * it is a change in kind: folding per keystroke instead of on write, or splitting the haystack per
 * note. Both were the previous behaviour and both would show up here immediately.
 */
class NoteQueryPerformanceTest {

    private companion object {
        const val NOTE_COUNT = 5_000
        const val BUDGET_MS = 50L
        const val NOW = 1_755_000_000_000L
    }

    private val words = listOf(
        "groceries", "meeting", "invoice", "holiday", "recipe", "reading", "project",
        "café", "Zürich", "naïve", "budget", "birthday", "insurance", "renewal"
    )

    /** Notes shaped like real ones: a title, a few lines of body, labels, some checklists. */
    private fun corpus(): List<Note> = (1..NOTE_COUNT).map { i ->
        val body = (0..12).joinToString(" ") { words[(i + it) % words.size] }
        val note = Note(
            id = i.toLong(),
            title = "${words[i % words.size]} ${words[(i * 7) % words.size]} $i",
            content = body,
            timestamp = NOW - i * 1_000L,
            color = if (i % 3 == 0) 0xFF2E5A32.toInt() else 0,
            isPinned = i % 50 == 0,
            isArchived = i % 11 == 0,
            isTrashed = i % 23 == 0,
            position = i,
            reminderTimestamp = if (i % 17 == 0) NOW + i else null,
            labels = if (i % 5 == 0) listOf(Label(id = (i % 7).toLong(), name = "label${i % 7}")) else emptyList(),
            checklist = if (i % 9 == 0) {
                listOf(ChecklistItem(id = i.toLong(), text = "buy ${words[i % words.size]}", position = 0))
            } else {
                emptyList()
            }
        )
        // Indexed, as every note is after the backfill. Measuring the unindexed path would be
        // measuring the fallback, which by design only ever covers a transient minority.
        note.copy(searchText = note.searchableText())
    }

    private fun timeOf(notes: List<Note>, query: NoteQuery): Long {
        // Warm up, so the first run's class loading and JIT are not attributed to the query.
        repeat(3) { NoteQueryMatcher.apply(notes, query, NOW) }
        return (1..5).minOf { measureTime { NoteQueryMatcher.apply(notes, query, NOW) }.inWholeMilliseconds }
    }

    private fun assertWithinBudget(name: String, notes: List<Note>, query: NoteQuery) {
        val ms = timeOf(notes, query)
        println("  query perf | $name: ${ms}ms over $NOTE_COUNT notes (budget ${BUDGET_MS}ms)")
        assertTrue(ms <= BUDGET_MS, "$name took ${ms}ms, over the ${BUDGET_MS}ms budget")
    }

    @Test
    fun `free text search stays within budget`() {
        assertWithinBudget("text", corpus(), NoteQuery(text = "cafe", sort = NoteSortOrder.NEWEST))
    }

    @Test
    fun `multi-token search stays within budget`() {
        assertWithinBudget(
            "multi-token",
            corpus(),
            NoteQuery(text = "cafe zurich", sort = NoteSortOrder.NEWEST)
        )
    }

    @Test
    fun `every dimension at once stays within budget`() {
        assertWithinBudget(
            "everything",
            corpus(),
            NoteQuery(
                text = "cafe",
                labels = setOf(1, 2, 3),
                colors = setOf(0xFF2E5A32.toInt()),
                flags = setOf(NoteFlag.HAS_CHECKLIST),
                sort = NoteSortOrder.OLDEST
            )
        )
    }

    @Test
    fun `an unfiltered list stays within budget`() {
        // The common case: no query at all, so the cost is scope plus the sort.
        assertWithinBudget("unfiltered", corpus(), NoteQuery(sort = NoteSortOrder.NEWEST))
    }
}
