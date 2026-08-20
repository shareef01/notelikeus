package com.aus.notelikeus.domain.model

import androidx.compose.runtime.Immutable

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

    /** Drops every narrowing dimension, keeping where you are and how you are looking at it. */
    fun cleared(): NoteQuery = NoteQuery(
        scope = scope,
        sort = sort,
        view = view,
        dateField = dateField,
        labelMatch = labelMatch
    )
}
