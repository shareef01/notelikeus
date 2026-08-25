package com.aus.notelikeus.domain.model

/**
 * What decides the order of the notes on screen, as opposed to which notes they are.
 *
 * Exists because the list's headings have to describe the order honestly, and the three orders
 * admit different honest descriptions: dates only make sense when the list is ordered by date.
 */
enum class NoteOrdering {
    /** Best match first, because a search is running. Position and date are both incidental. */
    RELEVANCE,

    /** The order the user dragged notes into. Nothing about it is derivable from a note's fields. */
    MANUAL,

    /** Newest or oldest first, so a note's date is also its place in the list. */
    DATE
}
