package com.aus.notelikeus.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Edit labels page refuses a second label of the same name and says how many notes use each. */
class LabelNamesTest {

    private val labels = listOf(Label(1, "Work"), Label(2, "Home  office"))

    @Test
    fun `a name differing only in case or spacing is taken`() {
        assertTrue(labels.hasLabelNamed("work"))
        assertTrue(labels.hasLabelNamed("  WORK "))
        assertTrue(labels.hasLabelNamed("home office"))
        assertFalse(labels.hasLabelNamed("Workshop"))
    }

    @Test
    fun `renaming a label to a new case of its own name is allowed`() {
        assertFalse(labels.hasLabelNamed("WORK", exceptId = 1))
        assertTrue(labels.hasLabelNamed("work", exceptId = 2))
    }

    @Test
    fun `the key matches web labelNameKey`() {
        assertEquals("home office", labelNameKey("  Home \t Office "))
    }

    @Test
    fun `counts each note once per label`() {
        fun note(vararg ids: Long) = Note(title = "", content = "", timestamp = 0, color = 0, labels = ids.map { Label(it, "l$it") })

        val counts = labelNoteCounts(listOf(note(1, 2), note(1), note(1, 1), note()))

        assertEquals(mapOf(1L to 3, 2L to 1), counts)
    }
}
