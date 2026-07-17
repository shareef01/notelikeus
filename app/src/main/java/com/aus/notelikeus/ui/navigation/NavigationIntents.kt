package com.aus.notelikeus.ui.navigation

import android.content.Intent

/** Set only by our own PendingIntents (widget, reminders). Blocks external noteId injection. */
const val EXTRA_INTERNAL_NAV = "com.aus.notelikeus.INTERNAL_NAV"

fun Intent.markInternalNavigation(): Intent = putExtra(EXTRA_INTERNAL_NAV, true)

fun extractEditorNoteId(intent: Intent?): Long? {
    if (intent?.getBooleanExtra(EXTRA_INTERNAL_NAV, false) != true) return null
    intent.getLongExtra("noteId", -1L).takeIf { it != -1L }?.let { return it }
    val uri = intent.data ?: return null
    if (uri.scheme == "notelikeus" && uri.host == "editor") {
        uri.pathSegments.firstOrNull()?.toLongOrNull()?.takeIf { it != -1L }?.let { return it }
        uri.lastPathSegment?.toLongOrNull()?.takeIf { it != -1L }?.let { return it }
    }
    return null
}

fun intentRequestsNewNote(intent: Intent?): Boolean {
    if (intent?.getBooleanExtra(EXTRA_INTERNAL_NAV, false) != true) return false
    return intent.getBooleanExtra("createNote", false)
}
