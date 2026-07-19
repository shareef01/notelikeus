package com.aus.notelikeus.ui.navigation

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Process-private token stamped into our own PendingIntents (widget, reminders).
 * A forgeable boolean extra is not a trust boundary — other apps can set it on an
 * exported MainActivity. They cannot read this MODE_PRIVATE preference.
 */
object InternalNavigationToken {
    private const val PREFS_NAME = "internal_nav_guard"
    private const val KEY_TOKEN = "token"

    @Volatile
    private var cached: String? = null

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) {
            cached = existing
            return
        }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_TOKEN, created).apply()
        cached = created
    }

    /** Test-only: reset cached token (prefs still hold value unless cleared). */
    internal fun resetForTests() {
        cached = null
    }

    fun current(): String =
        cached ?: error("InternalNavigationToken.init() must run before marking intents")

    fun matches(intent: Intent?): Boolean {
        val expected = cached ?: return false
        val provided = intent?.getStringExtra(EXTRA_INTERNAL_NAV_TOKEN) ?: return false
        return provided == expected
    }
}

/** Opaque token extra — do not treat a public boolean as proof of same-app origin. */
const val EXTRA_INTERNAL_NAV_TOKEN = "com.aus.notelikeus.INTERNAL_NAV_TOKEN"

/** @deprecated Prefer [EXTRA_INTERNAL_NAV_TOKEN]; kept only so old code paths compile. */
@Deprecated("Use EXTRA_INTERNAL_NAV_TOKEN")
const val EXTRA_INTERNAL_NAV = "com.aus.notelikeus.INTERNAL_NAV"

fun Intent.markInternalNavigation(): Intent =
    putExtra(EXTRA_INTERNAL_NAV_TOKEN, InternalNavigationToken.current())

fun extractEditorNoteId(intent: Intent?): Long? {
    if (intent == null || !InternalNavigationToken.matches(intent)) return null
    intent.getLongExtra("noteId", -1L).takeIf { it != -1L }?.let { return it }
    val uri = intent.data ?: return null
    if (uri.scheme == "notelikeus" && uri.host == "editor") {
        uri.pathSegments.firstOrNull()?.toLongOrNull()?.takeIf { it != -1L }?.let { return it }
        uri.lastPathSegment?.toLongOrNull()?.takeIf { it != -1L }?.let { return it }
    }
    return null
}

fun intentRequestsNewNote(intent: Intent?): Boolean {
    if (intent == null || !InternalNavigationToken.matches(intent)) return false
    return intent.getBooleanExtra("createNote", false)
}
