package com.aus.notelikeus.ui.editor

/**
 * Normalizes a note-authored URL the way the web client's `toSafeHref` does.
 *
 * Bare domains become https. http(s) and mailto pass through. Any other explicit scheme
 * (javascript:, data:, vbscript:, …) is rejected — note content can arrive unreviewed via typed
 * text or backup import, and Compose `LinkAnnotation.Url` would otherwise open it.
 */
object SafeHref {
    private val safeScheme = Regex("^(?:https?:|mailto:)", RegexOption.IGNORE_CASE)
    private val anyScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (safeScheme.containsMatchIn(trimmed)) return trimmed
        if (anyScheme.containsMatchIn(trimmed)) return null
        return "https://$trimmed"
    }
}
