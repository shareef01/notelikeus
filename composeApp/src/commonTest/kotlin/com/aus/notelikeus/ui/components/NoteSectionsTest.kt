package com.aus.notelikeus.ui.components

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteOrdering
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteSortOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A heading may only describe an order the list actually has.
 *
 * Date headings used to be emitted whatever the sort, which is wrong for two of the three orders.
 * Under a manual order a note's date says nothing about where it sits, so editing a note from last
 * week — which moves its timestamp to today but leaves its position alone — put a "Today" heading
 * in the middle of the list while another one still sat at the top. That case is
 * [a manual order does not get date headings, however the dates fall].
 */
class NoteSectionsTest {

    private val labels = NoteSectionLabels(
        pinned = "Pinned",
        others = "Others",
        today = "Today",
        yesterday = "Yesterday"
    )

    private val today = 1_000_000_000_000L
    private val yesterday = today - 86_400_000L
    private val lastWeek = today - 7 * 86_400_000L

    private fun note(id: Long, timestamp: Long, pinned: Boolean = false) =
        Note(id = id, title = "n$id", content = "", timestamp = timestamp, color = 0, isPinned = pinned)

    private fun headings(notes: List<Note>, ordering: NoteOrdering) = noteSectionHeadings(
        notes = notes,
        ordering = ordering,
        labels = labels,
        isToday = { it == today },
        isYesterday = { it == yesterday },
        dateHeading = { "Older" }
    )

    @Test
    fun `a date order gets one heading per day, at the day boundary`() {
        val notes = listOf(
            note(1, today),
            note(2, today),
            note(3, yesterday),
            note(4, lastWeek)
        )

        assertEquals(
            listOf("Today", null, "Yesterday", "Older"),
            headings(notes, NoteOrdering.DATE)
        )
    }

    /** The bug this exists to prevent. */
    @Test
    fun `a manual order does not get date headings, however the dates fall`() {
        // A note edited today sitting in the middle, exactly as a manual order leaves it.
        val notes = listOf(
            note(1, today),
            note(2, lastWeek),
            note(3, today),
            note(4, yesterday)
        )

        assertEquals(
            listOf(null, null, null, null),
            headings(notes, NoteOrdering.MANUAL)
        )
    }

    @Test
    fun `relevance order gets no headings at all`() {
        val notes = listOf(note(1, today, pinned = true), note(2, lastWeek))

        assertEquals(listOf(null, null), headings(notes, NoteOrdering.RELEVANCE))
    }

    @Test
    fun `pinned notes are their own section, above the dates`() {
        val notes = listOf(
            note(1, lastWeek, pinned = true),
            note(2, today, pinned = true),
            note(3, today),
            note(4, yesterday)
        )

        // No date headings inside the pinned block -- it is one group, not a dated one -- and the
        // first unpinned note starts the dates over regardless of what the last pinned note's date
        // was.
        assertEquals(
            listOf("Pinned", null, "Today", "Yesterday"),
            headings(notes, NoteOrdering.DATE)
        )
    }

    @Test
    fun `a manual order splits pinned from the rest`() {
        val notes = listOf(
            note(1, today, pinned = true),
            note(2, lastWeek),
            note(3, today)
        )

        assertEquals(listOf("Pinned", "Others", null), headings(notes, NoteOrdering.MANUAL))
    }

    /** "Others" is the counterpart to "Pinned". Alone, it divides nothing from nothing. */
    @Test
    fun `with nothing pinned there is no Others heading`() {
        val notes = listOf(note(1, today), note(2, lastWeek))

        assertEquals(listOf(null, null), headings(notes, NoteOrdering.MANUAL))
    }

    @Test
    fun `an empty list has no headings`() {
        assertEquals(emptyList(), headings(emptyList(), NoteOrdering.DATE))
    }

    @Test
    fun `every heading list lines up with its notes`() {
        val notes = List(6) { note(it.toLong(), today - it * 86_400_000L, pinned = it < 2) }

        for (ordering in NoteOrdering.entries) {
            assertEquals(notes.size, headings(notes, ordering).size, "$ordering")
        }
    }

    // ---- the query decides which of the three it is ----

    @Test
    fun `searching orders by relevance, whatever the sort says`() {
        for (sort in NoteSortOrder.entries) {
            assertEquals(
                NoteOrdering.RELEVANCE,
                NoteQuery(text = "milk", sort = sort).ordering,
                "$sort"
            )
        }
    }

    @Test
    fun `without a search the sort decides`() {
        assertEquals(NoteOrdering.MANUAL, NoteQuery(sort = NoteSortOrder.MANUAL).ordering)
        assertEquals(NoteOrdering.DATE, NoteQuery(sort = NoteSortOrder.NEWEST).ordering)
        assertEquals(NoteOrdering.DATE, NoteQuery(sort = NoteSortOrder.OLDEST).ordering)
    }
}
