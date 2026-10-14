package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The same rules as the web client's conflictReview.ts, on the same examples. */
class ConflictReviewTest {

    private val original = Note(id = 1L, title = "Groceries", content = "milk\neggs (from phone)", timestamp = 2_000L, color = 0)
    private val copy = Note(id = 2L, title = "Groceries (conflicted copy)", content = "milk\nbread (typed here)", timestamp = 3_000L, color = 0)

    @Test
    fun `finds the original by its title`() {
        assertEquals("Groceries", ConflictReview.originalTitleOf(copy.title))
        assertEquals(1L, ConflictReview.findOriginal(copy, listOf(original, copy))?.id)
    }

    @Test
    fun `refuses to guess between two candidates, a trashed note, or an untitled copy`() {
        assertNull(ConflictReview.findOriginal(copy, listOf(original, original.copy(id = 3L), copy)))
        assertNull(ConflictReview.findOriginal(copy, listOf(original.copy(isTrashed = true), copy)))
        val untitled = copy.copy(title = "Conflicted copy")
        assertNull(ConflictReview.findOriginal(untitled, listOf(original, untitled)))
    }

    @Test
    fun `marks the lines only one version has`() {
        val (a, b) = ConflictReview.compareVersions(original, copy)
        assertEquals(listOf(ReviewLine("milk", false), ReviewLine("eggs (from phone)", true)), a)
        assertEquals(listOf("bread (typed here)"), b.filter { it.changed }.map { it.text })
    }
}
