package com.aus.notelikeus.ui.components

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteOrdering

/** The heading text for each grouping the list can show. Resolved by the caller, so this is pure. */
data class NoteSectionLabels(
    val pinned: String,
    val others: String,
    val today: String,
    val yesterday: String
)

/**
 * Works out which heading, if any, belongs above each note.
 *
 * Pure and index-aligned with [notes] — element *i* is the heading to draw before note *i*, or null
 * for no heading — so the grid can render straight from it and this can be tested without a screen.
 *
 * The rule it exists to enforce: **a heading may only describe an order the list actually has.**
 * Date headings used to be emitted regardless of sort, which is wrong for two of the three orders.
 * Under a manual order a note's date says nothing about where it sits, so editing a note from last
 * week — which moves its timestamp to today but not its position — produced a "Today" heading in
 * the middle of the list, with another one still at the top. Mid-search the order is relevance, so
 * every heading is equally arbitrary.
 *
 * @param dateHeading formats a timestamp that is neither today nor yesterday. Passed in rather than
 *   called directly, because date formatting is platform code and this is not.
 */
fun noteSectionHeadings(
    notes: List<Note>,
    ordering: NoteOrdering,
    labels: NoteSectionLabels,
    isToday: (Long) -> Boolean,
    isYesterday: (Long) -> Boolean,
    dateHeading: (Long) -> String
): List<String?> {
    if (notes.isEmpty()) return emptyList()

    // Relevance answers "how well does this match", and no grouping of the notes describes that.
    if (ordering == NoteOrdering.RELEVANCE) return List(notes.size) { null }

    val firstUnpinned = notes.indexOfFirst { !it.isPinned }
    val hasPinned = notes.first().isPinned

    return notes.mapIndexed { index, note ->
        when {
            index == 0 && hasPinned -> labels.pinned

            // "Others" only earns its place as the counterpart to "Pinned". On its own, above a
            // list with nothing pinned, it is a heading that divides nothing from nothing.
            index == firstUnpinned && ordering == NoteOrdering.MANUAL ->
                if (hasPinned) labels.others else null

            note.isPinned -> null

            ordering == NoteOrdering.DATE -> {
                val heading = headingFor(note.timestamp, labels, isToday, isYesterday, dateHeading)
                val previous = notes.getOrNull(index - 1)
                val previousHeading = previous
                    ?.takeIf { !it.isPinned }
                    ?.let { headingFor(it.timestamp, labels, isToday, isYesterday, dateHeading) }
                heading.takeIf { it != previousHeading }
            }

            else -> null
        }
    }
}

private fun headingFor(
    timestamp: Long,
    labels: NoteSectionLabels,
    isToday: (Long) -> Boolean,
    isYesterday: (Long) -> Boolean,
    dateHeading: (Long) -> String
): String = when {
    isToday(timestamp) -> labels.today
    isYesterday(timestamp) -> labels.yesterday
    else -> dateHeading(timestamp)
}
