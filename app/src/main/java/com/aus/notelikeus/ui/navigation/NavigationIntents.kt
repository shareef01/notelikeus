package com.aus.notelikeus.ui.navigation

import android.content.Intent

fun extractEditorNoteId(intent: Intent?): Long? {
    intent?.getLongExtra("noteId", -1L)?.takeIf { it != -1L }?.let { return it }
    val uri = intent?.data ?: return null
    if (uri.scheme == "notelikeus" && uri.host == "editor") {
        uri.pathSegments.firstOrNull()?.toLongOrNull()?.takeIf { it != -1L }?.let { return it }
        uri.lastPathSegment?.toLongOrNull()?.takeIf { it != -1L }?.let { return it }
    }
    return null
}

fun intentRequestsNewNote(intent: Intent?): Boolean {
    return intent?.getBooleanExtra("createNote", false) == true
}
