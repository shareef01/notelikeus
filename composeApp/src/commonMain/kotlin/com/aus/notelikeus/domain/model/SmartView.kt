package com.aus.notelikeus.domain.model

/**
 * A named query the drawer offers directly.
 *
 * Every one of these was already expressible -- open Filters, find the status section, tick one
 * box -- so this adds no matching power. What it adds is a name and a place: the three questions
 * people actually ask a notes app repeatedly ("what did I set a reminder for", "what have I not
 * finished", "what did I never file") stop being a four-tap excavation through a sheet and become
 * one row in the drawer, next to the scopes they sit alongside conceptually.
 *
 * Deliberately not saved filters and deliberately not user-editable: these are fixed, so they can
 * be reasoned about, counted cheaply and named in the string table like any other navigation.
 *
 * Pinned is *not* here. The list already floats pinned notes into their own section at the top, so
 * a view of them would be a longer route to something already on screen.
 */
enum class SmartView(val flags: Set<NoteFlag>) {
    /** Anything with a reminder attached, past or future. */
    REMINDERS(setOf(NoteFlag.HAS_REMINDER)),

    /** Checklists with at least one item still unticked. */
    UNFINISHED(setOf(NoteFlag.HAS_UNCHECKED_ITEMS)),

    /** Notes that never got filed under a label. */
    UNLABELED(setOf(NoteFlag.UNLABELED));

    /**
     * The query this view means, built from whatever is on screen now.
     *
     * Clears the other filters rather than adding to them. A smart view is a destination, not a
     * refinement -- tapping "Reminders" while a colour filter happened to be on and landing on
     * "reminders that are also blue" would be a different list than the row promises, and the row
     * is what the user read. Sort and view survive because they are how the user likes to look at
     * a list, not part of which list it is.
     */
    fun applyTo(query: NoteQuery): NoteQuery =
        query.cleared().copy(scope = NoteScope.ACTIVE, flags = flags)

    /**
     * Whether this view is exactly what is on screen.
     *
     * Stated as "applying it would change nothing", which is the same question and cannot drift
     * out of step with [applyTo] the way a hand-written field-by-field comparison would.
     */
    fun isActive(query: NoteQuery): Boolean = query == applyTo(query)
}
