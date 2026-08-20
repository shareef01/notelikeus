package com.aus.notelikeus.domain.query

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.DateRange
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.LabelMatch
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.model.NoteSortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_755_000_000_000L
private const val DAY = 86_400_000L

private fun note(
    id: Long = 1,
    title: String = "",
    content: String = "",
    timestamp: Long = NOW,
    color: Int = 0,
    isPinned: Boolean = false,
    isArchived: Boolean = false,
    isTrashed: Boolean = false,
    position: Int = 0,
    reminderTimestamp: Long? = null,
    labels: List<Label> = emptyList(),
    checklist: List<ChecklistItem> = emptyList()
) = Note(
    id = id, title = title, content = content, timestamp = timestamp, color = color,
    isPinned = isPinned, isArchived = isArchived, isTrashed = isTrashed, position = position,
    reminderTimestamp = reminderTimestamp, labels = labels, checklist = checklist
)

private fun label(id: Long, name: String = "l$id") = Label(id = id, name = name)
private fun item(text: String, checked: Boolean = false) =
    ChecklistItem(id = 0, text = text, isChecked = checked, position = 0)

private fun matches(n: Note, q: NoteQuery) = NoteQueryMatcher.matches(n, q, NOW)

class NoteQueryMatcherTest {

    // ---- scope ----

    @Test
    fun `scope selects the right pile`() {
        val active = note(id = 1)
        val archived = note(id = 2, isArchived = true)
        val trashed = note(id = 3, isTrashed = true)
        // A trashed note that was archived first is in Trash, not Archive: trash wins, or a note
        // would appear in two places at once.
        val archivedAndTrashed = note(id = 4, isArchived = true, isTrashed = true)

        val q = NoteQuery()
        assertTrue(matches(active, q.copy(scope = NoteScope.ACTIVE)))
        assertFalse(matches(archived, q.copy(scope = NoteScope.ACTIVE)))
        assertFalse(matches(trashed, q.copy(scope = NoteScope.ACTIVE)))

        assertTrue(matches(archived, q.copy(scope = NoteScope.ARCHIVE)))
        assertFalse(matches(archivedAndTrashed, q.copy(scope = NoteScope.ARCHIVE)))

        assertTrue(matches(trashed, q.copy(scope = NoteScope.TRASH)))
        assertTrue(matches(archivedAndTrashed, q.copy(scope = NoteScope.TRASH)))

        listOf(active, archived, trashed, archivedAndTrashed).forEach {
            assertTrue(matches(it, q.copy(scope = NoteScope.ALL)))
        }
    }

    // ---- text ----

    @Test
    fun `text matches across title body checklist and label names`() {
        val n = note(
            title = "Groceries",
            content = "milk and eggs",
            checklist = listOf(item("bread")),
            labels = listOf(label(1, "Errands"))
        )
        listOf("groceries", "milk", "bread", "errands").forEach {
            assertTrue(matches(n, NoteQuery(text = it)), "'$it' should match")
        }
    }

    @Test
    fun `all tokens must match and order does not matter`() {
        val n = note(title = "Bread and milk")
        assertTrue(matches(n, NoteQuery(text = "milk bread")))
        assertFalse(matches(n, NoteQuery(text = "milk cheese")))
    }

    @Test
    fun `matching is by prefix, not substring`() {
        // The deliberate narrowing: contains() matched mid-word, so "ote" found "notes".
        val n = note(title = "notes")
        assertTrue(matches(n, NoteQuery(text = "not")))
        assertFalse(matches(n, NoteQuery(text = "ote")))
    }

    @Test
    fun `text is diacritic and case insensitive both ways`() {
        assertTrue(matches(note(title = "Café Zürich"), NoteQuery(text = "cafe")))
        assertTrue(matches(note(title = "Cafe Zurich"), NoteQuery(text = "café")))
    }

    @Test
    fun `blank text matches everything`() {
        assertTrue(matches(note(), NoteQuery(text = "   ")))
    }

    // ---- colours ----

    @Test
    fun `colour is a set and empty means any`() {
        val green = note(color = 0xFF2E5A32.toInt())
        assertTrue(matches(green, NoteQuery()))
        assertTrue(matches(green, NoteQuery(colors = setOf(0xFF2E5A32.toInt(), 0xFF2A4A6E.toInt()))))
        assertFalse(matches(green, NoteQuery(colors = setOf(0xFF2A4A6E.toInt()))))
    }

    // ---- labels ----

    @Test
    fun `label ANY matches one of, ALL requires every one`() {
        val n = note(labels = listOf(label(1), label(2)))
        val any = NoteQuery(labels = setOf(2, 3), labelMatch = LabelMatch.ANY)
        val all = NoteQuery(labels = setOf(1, 2), labelMatch = LabelMatch.ALL)
        val allMissing = NoteQuery(labels = setOf(1, 3), labelMatch = LabelMatch.ALL)
        assertTrue(matches(n, any))
        assertTrue(matches(n, all))
        assertFalse(matches(n, allMissing))
    }

    @Test
    fun `an unlabelled note matches no label selection`() {
        val n = note()
        assertFalse(matches(n, NoteQuery(labels = setOf(1), labelMatch = LabelMatch.ANY)))
        assertFalse(matches(n, NoteQuery(labels = setOf(1), labelMatch = LabelMatch.ALL)))
    }

    // ---- flags ----

    @Test
    fun `every flag is evaluated correctly`() {
        fun q(f: NoteFlag) = NoteQuery(flags = setOf(f))
        assertTrue(matches(note(isPinned = true), q(NoteFlag.PINNED)))
        assertFalse(matches(note(), q(NoteFlag.PINNED)))

        assertTrue(matches(note(reminderTimestamp = NOW + DAY), q(NoteFlag.HAS_REMINDER)))
        assertFalse(matches(note(), q(NoteFlag.HAS_REMINDER)))

        assertTrue(matches(note(reminderTimestamp = NOW - 1), q(NoteFlag.REMINDER_OVERDUE)))
        assertFalse(matches(note(reminderTimestamp = NOW + DAY), q(NoteFlag.REMINDER_OVERDUE)))
        assertFalse(matches(note(), q(NoteFlag.REMINDER_OVERDUE)))

        assertTrue(matches(note(checklist = listOf(item("a"))), q(NoteFlag.HAS_CHECKLIST)))
        assertFalse(matches(note(), q(NoteFlag.HAS_CHECKLIST)))

        assertTrue(matches(note(checklist = listOf(item("a"))), q(NoteFlag.HAS_UNCHECKED_ITEMS)))
        assertFalse(
            matches(note(checklist = listOf(item("a", checked = true))), q(NoteFlag.HAS_UNCHECKED_ITEMS))
        )

        assertTrue(matches(note(content = "see https://x.dev"), q(NoteFlag.HAS_LINKS)))
        assertTrue(matches(note(content = "see www.x.dev"), q(NoteFlag.HAS_LINKS)))
        assertFalse(matches(note(content = "no link"), q(NoteFlag.HAS_LINKS)))

        assertTrue(matches(note(title = "  "), q(NoteFlag.UNTITLED)))
        assertFalse(matches(note(title = "t"), q(NoteFlag.UNTITLED)))

        assertTrue(matches(note(), q(NoteFlag.UNLABELED)))
        assertFalse(matches(note(labels = listOf(label(1))), q(NoteFlag.UNLABELED)))
    }

    @Test
    fun `multiple flags are ANDed`() {
        val q = NoteQuery(flags = setOf(NoteFlag.PINNED, NoteFlag.HAS_CHECKLIST))
        assertTrue(matches(note(isPinned = true, checklist = listOf(item("a"))), q))
        assertFalse(matches(note(isPinned = true), q))
    }

    // ---- dates ----

    @Test
    fun `date range is inclusive of start and exclusive of end`() {
        val range = DateRange(NOW, NOW + DAY)
        val q = NoteQuery(dateField = DateField.EDITED, dateRange = range)
        assertTrue(matches(note(timestamp = NOW), q))
        assertTrue(matches(note(timestamp = NOW + DAY - 1), q))
        assertFalse(matches(note(timestamp = NOW + DAY), q))
        assertFalse(matches(note(timestamp = NOW - 1), q))
    }

    @Test
    fun `a reminder date range excludes notes with no reminder`() {
        val q = NoteQuery(dateField = DateField.REMINDER, dateRange = DateRange(NOW, NOW + DAY))
        assertTrue(matches(note(reminderTimestamp = NOW + 1), q))
        assertFalse(matches(note(reminderTimestamp = null), q))
    }

    // ---- sorting ----

    @Test
    fun `pinned notes lead in every sort mode`() {
        val notes = listOf(
            note(id = 1, timestamp = NOW + 100),
            note(id = 2, timestamp = NOW, isPinned = true),
            note(id = 3, timestamp = NOW + 200)
        )
        NoteSortOrder.entries.forEach { order ->
            assertEquals(2L, NoteQueryMatcher.sort(notes, order).first().id, "$order")
        }
    }

    @Test
    fun `newest and oldest order by timestamp`() {
        val notes = listOf(
            note(id = 1, timestamp = NOW + 100),
            note(id = 2, timestamp = NOW),
            note(id = 3, timestamp = NOW + 200)
        )
        assertEquals(
            listOf(3L, 1L, 2L),
            NoteQueryMatcher.sort(notes, NoteSortOrder.NEWEST).map { it.id })
        assertEquals(
            listOf(2L, 1L, 3L),
            NoteQueryMatcher.sort(notes, NoteSortOrder.OLDEST).map { it.id })
    }

    @Test
    fun `manual order follows position`() {
        val notes = listOf(
            note(id = 1, position = 2),
            note(id = 2, position = 0),
            note(id = 3, position = 1)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            NoteQueryMatcher.sort(notes, NoteSortOrder.MANUAL).map { it.id })
    }

    // ---- the whole thing ----

    @Test
    fun `apply filters then sorts`() {
        val notes = listOf(
            note(id = 1, title = "alpha", timestamp = NOW + 10),
            note(id = 2, title = "beta", timestamp = NOW + 20),
            note(id = 3, title = "alpine", timestamp = NOW + 30, isTrashed = true)
        )
        val result = NoteQueryMatcher.apply(notes, NoteQuery(text = "alp", sort = NoteSortOrder.NEWEST), NOW)
        // note 3 matches the text but is in Trash, and the default scope is ACTIVE.
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `hasActiveFilters ignores scope sort and view`() {
        assertFalse(NoteQuery(scope = NoteScope.TRASH, sort = NoteSortOrder.OLDEST).hasActiveFilters)
        assertTrue(NoteQuery(text = "x").hasActiveFilters)
        assertTrue(NoteQuery(labels = setOf(1)).hasActiveFilters)
        assertTrue(NoteQuery(colors = setOf(1)).hasActiveFilters)
        assertTrue(NoteQuery(flags = setOf(NoteFlag.PINNED)).hasActiveFilters)
        assertTrue(NoteQuery(dateRange = DateRange(0, 1)).hasActiveFilters)
    }

    @Test
    fun `cleared keeps where you are and how you are looking`() {
        val q = NoteQuery(
            text = "x", labels = setOf(1), colors = setOf(2), flags = setOf(NoteFlag.PINNED),
            dateRange = DateRange(0, 1), scope = NoteScope.ARCHIVE, sort = NoteSortOrder.NEWEST
        )
        val cleared = q.cleared()
        assertFalse(cleared.hasActiveFilters)
        assertEquals(NoteScope.ARCHIVE, cleared.scope)
        assertEquals(NoteSortOrder.NEWEST, cleared.sort)
    }
}
