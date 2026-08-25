package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Which pile of notes is being looked at. Replaces the old `NoteFilter` enum. */
enum class NoteScope { ACTIVE, ARCHIVE, TRASH, ALL }

/** How a multi-label selection combines. */
enum class LabelMatch { ANY, ALL }

/** Which timestamp a date range is applied to. */
enum class DateField { CREATED, EDITED, REMINDER }

/**
 * A boolean property of a note, selectable as a filter.
 *
 * Every one is derivable from the note itself, so nothing here needs storing — they are questions
 * asked of a note, not fields on it.
 */
enum class NoteFlag {
    PINNED,
    HAS_REMINDER,
    REMINDER_OVERDUE,
    HAS_CHECKLIST,
    HAS_UNCHECKED_ITEMS,
    HAS_LINKS,
    UNTITLED,
    UNLABELED
}

/** Inclusive-start, exclusive-end epoch-millis window. */
@Immutable
@Serializable
data class DateRange(val fromInclusive: Long, val toExclusive: Long) {
    operator fun contains(timestamp: Long): Boolean =
        timestamp >= fromInclusive && timestamp < toExclusive
}

/**
 * Everything that decides which notes are on screen and in what order.
 *
 * This replaces five independent fields on `MainState` — `searchQuery`, `selectedColor`,
 * `selectedLabelId`, `sortOrder`, `currentFilter` — each of which had its own setter and its own
 * path into the recompute. There were six such paths, and adding a sixth filter dimension would
 * have meant a seventh. One object with one setter means the recompute has exactly one trigger.
 *
 * Colour and label are sets rather than the single nullable values they replace, which is what
 * lets "green or blue" and "tagged both work and urgent" be expressible at all.
 *
 * Deliberately not `Serializable`: it is UI state, restored through `SavedStateHandle` field by
 * field, and making it a wire format would invite it into the backup or the cloud document, where
 * it does not belong.
 */
@Immutable
@Serializable
data class NoteQuery(
    val text: String = "",
    val labels: Set<Long> = emptySet(),
    val labelMatch: LabelMatch = LabelMatch.ANY,
    val colors: Set<Int> = emptySet(),
    val flags: Set<NoteFlag> = emptySet(),
    val dateField: DateField = DateField.EDITED,
    val dateRange: DateRange? = null,
    val scope: NoteScope = NoteScope.ACTIVE,
    val sort: NoteSortOrder = NoteSortOrder.MANUAL,
    val view: NoteViewMode = NoteViewMode.GRID_2
) {
    /**
     * Whether anything is narrowing the list.
     *
     * Scope, sort and view are excluded on purpose: being in Trash, sorted oldest-first, in grid
     * view is not "filtered", and treating it as such would keep a "Clear filters" affordance
     * permanently lit.
     */
    val hasActiveFilters: Boolean
        get() = text.isNotBlank() ||
            labels.isNotEmpty() ||
            colors.isNotEmpty() ||
            flags.isNotEmpty() ||
            dateRange != null

    /**
     * Whether dragging notes into a manual order is meaningful right now.
     *
     * Manual reordering and automatic sorting are mutually exclusive, and the UI used to promise
     * something it could not honour: the drag handles appeared whenever the list was single-column,
     * regardless of sort. Dragging under "Newest first" did not merely fail to stick -- it wrote.
     * `updateNotePositions` bumps each moved note's `timestamp` so the new position survives the
     * sync conflict guard, so a drag under a timestamp sort **rewrote the timestamps of every note
     * it touched and replicated them to every device**, then re-sorted the list out from under the
     * user. That is data loss with a cosmetic disguise.
     *
     * Filters exclude it for a smaller reason: position is a property of the whole list, and
     * dragging within a filtered subset cannot express where the note goes among the notes that
     * are hidden.
     */
    val allowsManualReorder: Boolean
        get() = sort == NoteSortOrder.MANUAL && !hasActiveFilters

    /**
     * What the list's order actually groups by.
     *
     * Headings can only describe an order that exists. Date headings over a manually ordered list
     * name a grouping the list does not have -- edit a note from last week and its timestamp jumps
     * to today while its position does not, so "Today" appears in the middle of the list, twice.
     * The same is true mid-search, where the order is relevance and the dates are incidental.
     */
    val ordering: NoteOrdering
        get() = when {
            // Searching overrides the chosen sort with relevance; see NoteQueryMatcher.search.
            text.isNotEmpty() -> NoteOrdering.RELEVANCE
            sort == NoteSortOrder.MANUAL -> NoteOrdering.MANUAL
            else -> NoteOrdering.DATE
        }

    /**
     * Whether one tap on the sort would make reordering possible.
     *
     * The difference between a blocker worth explaining and one worth hiding. An automatic sort is
     * a choice the user made and can unmake, so a drag under one should say so and offer the
     * switch. An active filter is not: positions are global, so there is no sort that makes
     * dragging within a filtered subset mean anything, and offering to fix it would be offering
     * something that does not.
     */
    val switchingSortWouldAllowReorder: Boolean
        get() = sort != NoteSortOrder.MANUAL && !hasActiveFilters

    /**
     * Just the narrowing half: which notes, with how they are displayed reset to the defaults.
     *
     * What a saved filter stores. A saved filter names a set of notes, not a way of looking at
     * one -- restoring "Invoices" should not also flip the list back to two columns sorted oldest
     * because that happened to be on screen when it was saved, and sort and view are persisted
     * preferences, so writing them from a shortcut would change a setting the user did not touch.
     *
     * Also what makes "is this saved filter the one on screen" an exact comparison rather than a
     * field-by-field one that has to be kept in step by hand.
     */
    fun narrowingOnly(): NoteQuery = copy(sort = Default.sort, view = Default.view)

    /** Drops every narrowing dimension, keeping where you are and how you are looking at it. */
    fun cleared(): NoteQuery = NoteQuery(
        scope = scope,
        sort = sort,
        view = view,
        dateField = dateField,
        labelMatch = labelMatch
    )

    companion object {
        /** The defaults, named once so nothing has to repeat the constructor's values. */
        val Default = NoteQuery()
    }
}
