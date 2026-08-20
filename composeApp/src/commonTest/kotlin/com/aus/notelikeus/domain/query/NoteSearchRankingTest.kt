package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteSortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_755_000_000_000L

private fun note(
    id: Long,
    title: String = "",
    content: String = "",
    timestamp: Long = NOW,
    isPinned: Boolean = false,
    labels: List<Label> = emptyList(),
    checklist: List<ChecklistItem> = emptyList()
): Note {
    val n = Note(
        id = id, title = title, content = content, timestamp = timestamp, color = 0,
        isPinned = isPinned, labels = labels, checklist = checklist
    )
    return n.copy(searchText = n.searchableText())
}

class NoteSearchRankingTest {

    @Test
    fun `title beats label beats checklist beats body`() {
        val inTitle = note(1, title = "budget")
        val inLabel = note(2, title = "x", labels = listOf(Label(1, "budget")))
        val inChecklist = note(3, title = "x", checklist = listOf(ChecklistItem(1, "budget", false, 0)))
        val inBody = note(4, title = "x", content = "budget")

        val ordered = NoteSearchRanking.byRelevance(
            listOf(inBody, inChecklist, inLabel, inTitle), "budget"
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), ordered.map { it.id })
    }

    @Test
    fun `an exact title beats a title that merely contains the words`() {
        // Someone typing a note's whole title wants that note, not a longer one mentioning it.
        val exact = note(1, title = "Weekly budget")
        val longer = note(2, title = "Weekly budget review and planning notes")
        val ordered = NoteSearchRanking.byRelevance(listOf(longer, exact), "weekly budget")
        assertEquals(listOf(1L, 2L), ordered.map { it.id })
    }

    @Test
    fun `matching both words in the title beats splitting them across fields`() {
        val both = note(1, title = "milk bread")
        val split = note(2, title = "milk", content = "bread")
        val ordered = NoteSearchRanking.byRelevance(listOf(split, both), "milk bread")
        assertEquals(listOf(1L, 2L), ordered.map { it.id })
    }

    @Test
    fun `equal relevance falls back to most recently edited`() {
        val older = note(1, title = "budget", timestamp = NOW - 1000)
        val newer = note(2, title = "budget", timestamp = NOW)
        val ordered = NoteSearchRanking.byRelevance(listOf(older, newer), "budget")
        assertEquals(listOf(2L, 1L), ordered.map { it.id })
    }

    @Test
    fun `pinned notes still lead even when they score lower`() {
        val pinnedWeak = note(1, title = "x", content = "budget", isPinned = true)
        val unpinnedStrong = note(2, title = "budget")
        val ordered = NoteSearchRanking.byRelevance(listOf(unpinnedStrong, pinnedWeak), "budget")
        assertEquals(listOf(1L, 2L), ordered.map { it.id })
    }

    @Test
    fun `an empty query leaves the order alone`() {
        val notes = listOf(note(1), note(2), note(3))
        assertEquals(notes.map { it.id }, NoteSearchRanking.byRelevance(notes, "  ").map { it.id })
    }
}

class EditDistanceTest {

    private fun within(a: String, b: String, max: Int) =
        NoteSearchRanking.editDistanceWithin(a, b, max)

    @Test
    fun `identical strings are zero edits apart`() {
        assertTrue(within("budget", "budget", 0))
    }

    @Test
    fun `counts substitutions insertions and deletions`() {
        assertTrue(within("budget", "budgot", 1))
        assertTrue(within("budget", "budgets", 1))
        assertTrue(within("budget", "budgt", 1))
        assertFalse(within("budget", "budgot", 0))
    }

    @Test
    fun `respects the budget`() {
        assertTrue(within("kitten", "sitting", 3))
        assertFalse(within("kitten", "sitting", 2))
    }

    @Test
    fun `a length gap wider than the budget is rejected immediately`() {
        assertFalse(within("a", "abcdefgh", 2))
        assertFalse(within("abcdefgh", "a", 2))
    }

    @Test
    fun `empty strings behave`() {
        assertTrue(within("", "", 0))
        assertTrue(within("", "ab", 2))
        assertFalse(within("", "abc", 2))
    }
}

class FuzzyFallbackTest {

    private val notes = listOf(
        note(1, title = "Budget review"),
        note(2, title = "Groceries"),
        note(3, title = "Holiday plans")
    )

    private fun search(text: String) =
        NoteQueryMatcher.search(notes, NoteQuery(text = text, sort = NoteSortOrder.NEWEST), NOW)

    @Test
    fun `an exact match never triggers the fallback`() {
        val result = search("budget")
        assertFalse(result.isFuzzy)
        assertEquals(listOf(1L), result.notes.map { it.id })
    }

    @Test
    fun `a typo falls back to near matches and says so`() {
        val result = search("budgte")
        assertTrue(result.isFuzzy, "a two-edit typo should have fallen back")
        assertEquals(listOf(1L), result.notes.map { it.id })
    }

    @Test
    fun `something genuinely absent still returns nothing`() {
        // The fallback must not turn every empty search into a pile of unrelated notes.
        val result = search("submarine")
        assertFalse(result.isFuzzy)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `short tokens are not corrected`() {
        // At three characters nearly everything is two edits from everything, so correcting them
        // produces noise rather than suggestions.
        val result = search("bud")
        assertFalse(result.isFuzzy)
        assertEquals(listOf(1L), result.notes.map { it.id }, "prefix matching should still work")

        val nonsense = search("xyz")
        assertTrue(nonsense.notes.isEmpty())
    }

    @Test
    fun `the fallback keeps every non-text filter exact`() {
        // A typo in the text must not quietly widen the colour or the scope too.
        val coloured = notes.map { it.copy(color = 0xFF2E5A32.toInt()) }
        val result = NoteQueryMatcher.search(
            coloured,
            NoteQuery(text = "budgte", colors = setOf(0xFF6D2B2B.toInt())),
            NOW
        )
        assertTrue(result.notes.isEmpty(), "no note has the requested colour, typo or not")
    }

    @Test
    fun `an empty text query is never fuzzy`() {
        val result = NoteQueryMatcher.search(notes, NoteQuery(), NOW)
        assertFalse(result.isFuzzy)
        assertEquals(3, result.notes.size)
    }
}
