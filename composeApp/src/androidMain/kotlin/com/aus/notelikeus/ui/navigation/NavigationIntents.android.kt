package com.aus.notelikeus.ui.navigation

import android.content.Context
import android.content.Intent
import java.util.UUID

actual object InternalNavigationToken {
    private var token: String? = null

    actual fun init(context: Any) {
        if (token == null) {
            token = UUID.randomUUID().toString()
        }
    }

    actual fun current(): String = token ?: ""

    actual fun matches(intent: Any?): Boolean {
        val i = intent as? Intent ?: return false
        return i.getStringExtra(EXTRA_INTERNAL_NAV_TOKEN) == current()
    }
}

actual fun extractEditorNoteId(intent: Any?): Long? {
    val i = intent as? Intent ?: return null
    if (!InternalNavigationToken.matches(i)) return null
    
    // Check extra first
    val idFromExtra = i.getLongExtra("noteId", -1L).takeIf { it != -1L }
    if (idFromExtra != null) return idFromExtra

    // Check deep link
    val data = i.data ?: return null
    if (data.scheme == "notelikeus" && data.host == "editor") {
        return data.lastPathSegment?.toLongOrNull()
    }
    return null
}

actual fun intentRequestsNewNote(intent: Any?): Boolean {
    val i = intent as? Intent ?: return false
    if (!InternalNavigationToken.matches(i)) return false
    return i.getBooleanExtra("createNote", false)
}

fun Intent.markInternalNavigation(): Intent =
    putExtra(EXTRA_INTERNAL_NAV_TOKEN, InternalNavigationToken.current())
