package com.aus.notelikeus.domain.model

import kotlinx.serialization.Serializable

/**
 * A [NoteQuery] the user named and kept.
 *
 * [SmartView] covers the questions everyone asks; this covers the ones only this user asks --
 * "invoices from last month", "the green ones about the house". Rebuilding those through the
 * Filters sheet every time is the friction the whole query system exists to remove, and the only
 * thing standing between a query and a shortcut is a name.
 *
 * Stored as JSON in settings rather than in the notes database, deliberately. These are a lens on
 * the user's data, not the data: losing one costs a few taps, and the alternative -- a new table,
 * a schema migration and a sync story -- is a large amount of risk to protect something that
 * cheap. [name] is the identity, so saving over an existing name replaces it, the way a file does.
 */
@Serializable
data class SavedFilter(
    val name: String,
    val query: NoteQuery
) {
    companion object {
        /** Long enough to be descriptive, short enough to sit in a drawer row. */
        const val MAX_NAME_LENGTH = 40

        /**
         * A cap, because this is unbounded user input in a preferences blob.
         *
         * Nothing enforces a size limit on a DataStore value, so without one a runaway loop or a
         * determined user could grow the settings file until reading it is slow on every launch.
         */
        const val MAX_SAVED = 20
    }
}
