package com.aus.notelikeus.ui.navigation

/**
 * Process-private token stamped into our own PendingIntents (widget, reminders).
 */
expect object InternalNavigationToken {
    fun init(context: Any)
    fun current(): String
    fun matches(intent: Any?): Boolean
}

/** Opaque token extra — do not treat a public boolean as proof of same-app origin. */
const val EXTRA_INTERNAL_NAV_TOKEN = "com.aus.notelikeus.INTERNAL_NAV_TOKEN"

/** @deprecated Prefer [EXTRA_INTERNAL_NAV_TOKEN]; kept only so old code paths compile. */
@Deprecated("Use EXTRA_INTERNAL_NAV_TOKEN")
const val EXTRA_INTERNAL_NAV = "com.aus.notelikeus.INTERNAL_NAV"

expect fun extractEditorNoteId(intent: Any?): Long?

expect fun intentRequestsNewNote(intent: Any?): Boolean
