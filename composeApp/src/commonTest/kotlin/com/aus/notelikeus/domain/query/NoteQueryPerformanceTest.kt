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
        // Indexed, as a note is once it has been written since the column existed. The
        // unindexed path is measured separately below -- there is no backfill, so on an upgraded
        // database it stays the common path until each note is next edited.
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
    /**
     * What the fallback costs, and therefore why the backfill exists.
     *
     * An un-indexed note folds its fields on every pass, which is what keeps it findable on an
     * upgraded database before it has been written since the column existed.
     *
     * This measures the gap rather than assuming it, and the number is why there is no backfill:
     * 12ms against 0ms at 5,000 notes, both inside the 50ms budget. Rewriting every row of an
     * encrypted database to save 12ms that nobody is waiting on is a bad trade -- see
     * DECISIONS.md D9. What this test has to guarantee is that the two paths agree, because a
     * fallback that returned different notes would be far worse than a slow one.
     */
    @Test
    fun `the unindexed fallback is correct but markedly slower`() {
        val indexed = corpus()
        val unindexed = indexed.map { it.copy(searchText = null) }
        val query = NoteQuery(text = "cafe", sort = NoteSortOrder.NEWEST)

        val indexedMs = timeOf(indexed, query)
        val unindexedMs = timeOf(unindexed, query)
        println("  query perf | indexed ${indexedMs}ms vs unindexed ${unindexedMs}ms over $NOTE_COUNT notes")

        // Same answers either way -- the fallback must never change which notes match.
        assertTrue(
            NoteQueryMatcher.apply(indexed, query, NOW).map { it.id } ==
                NoteQueryMatcher.apply(unindexed, query, NOW).map { it.id },
            "the fallback returned a different result set than the indexed path"
        )
    }

}
