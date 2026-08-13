package com.aus.notelikeus.ui.navigation

actual object InternalNavigationToken {
    actual fun init(context: Any) {}
    actual fun current(): String = ""
    actual fun matches(intent: Any?): Boolean = true
}

actual fun extractEditorNoteId(intent: Any?): Long? = null

actual fun intentRequestsNewNote(intent: Any?): Boolean = false
