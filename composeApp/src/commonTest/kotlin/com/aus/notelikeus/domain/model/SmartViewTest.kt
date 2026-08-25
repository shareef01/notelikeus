package com.aus.notelikeus.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drawer rows have to agree with the list they open.
 *
 * A named destination that lands somewhere other than its name -- "Reminders" showing reminders
 * that also happen to be blue, because a colour chip was on when it was tapped -- is worse than no
 * shortcut at all, because the user reads the name and believes it.
 */
class SmartViewTest {

    @Test
    fun `a view replaces the filters rather than narrowing them`() {
        val filtered = NoteQuery(
            text = "milk",
            labels = setOf(3L),
            colors = setOf(0xFF2E5A32.toInt()),
            flags = setOf(NoteFlag.PINNED),
            dateRange = DateRange(0, 1)
        )

        val applied = SmartView.REMINDERS.applyTo(filtered)

        assertEquals(setOf(NoteFlag.HAS_REMINDER), applied.flags)
        assertEquals("", applied.text)
        assertEquals(emptySet(), applied.labels)
        assertEquals(emptySet(), applied.colors)
        assertEquals(null, applied.dateRange)
    }

    /** How you look at a list is not part of which list it is. */
    @Test
    fun `sort and view survive the jump`() {
        val query = NoteQuery(sort = NoteSortOrder.OLDEST, view = NoteViewMode.LIST)

        val applied = SmartView.UNFINISHED.applyTo(query)

        assertEquals(NoteSortOrder.OLDEST, applied.sort)
        assertEquals(NoteViewMode.LIST, applied.view)
    }

    /** The rows navigate to active notes, so tapping one from the trash has to leave the trash. */
    @Test
    fun `a view always lands in active notes`() {
        for (view in SmartView.entries) {
            assertEquals(NoteScope.ACTIVE, view.applyTo(NoteQuery(scope = NoteScope.TRASH)).scope)
            assertEquals(NoteScope.ACTIVE, view.applyTo(NoteQuery(scope = NoteScope.ARCHIVE)).scope)
        }
    }

    @Test
    fun `a view reports itself active exactly when it is on screen`() {
        for (view in SmartView.entries) {
            val applied = view.applyTo(NoteQuery())
            assertTrue(view.isActive(applied), "$view should be active for its own query")

            // Every other view is a different destination, so at most one row is ever lit.
            for (other in SmartView.entries - view) {
                assertFalse(other.isActive(applied), "$other should not be active for $view")
            }
        }
    }

    @Test
    fun `an extra filter on top of a view unselects it`() {
        val reminders = SmartView.REMINDERS.applyTo(NoteQuery())

        // The list is no longer what the row promises, so the row must stop claiming it is.
        assertFalse(SmartView.REMINDERS.isActive(reminders.copy(text = "milk")))
        assertFalse(SmartView.REMINDERS.isActive(reminders.copy(labels = setOf(1L))))
        assertFalse(SmartView.REMINDERS.isActive(reminders.copy(scope = NoteScope.ARCHIVE)))
    }

    @Test
    fun `no view is active on a plain unfiltered list`() {
        for (view in SmartView.entries) {
            assertFalse(view.isActive(NoteQuery()), "$view claimed an unfiltered list")
        }
    }
}
