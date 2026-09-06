package com.aus.notelikeus.ui.navigation

import android.content.Context
import android.content.Intent
import com.aus.notelikeus.data.backup.NoteBackupImporter
import java.util.UUID

actual object InternalNavigationToken {
    private var token: String? = null

    actual fun init(context: Any) {
        if (token != null) return
        val ctx = (context as Context).applicationContext
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        if (stored != null) {
            token = stored
            return
        }
        val created = UUID.randomUUID().toString()
        // commit(): the first launch must have the value on disk before a widget PendingIntent
        // can be built, and tests simulate process death by forgetting the in-memory copy.
        prefs.edit().putString(KEY, created).commit()
        token = created
    }

    actual fun current(): String = token ?: ""

    actual fun matches(intent: Any?): Boolean {
        val i = intent as? Intent ?: return false
        return i.getStringExtra(EXTRA_INTERNAL_NAV_TOKEN) == current()
    }

    /** Drops the in-memory copy so the next [init] must reload from disk (process-death stand-in). */
    internal fun forgetInMemoryForTests() {
        token = null
    }

    private const val KEY = "token"
    internal const val PREFS_NAME = "notelikeus_internal_nav"
}

actual fun extractEditorNoteId(intent: Any?): Long? {
    val i = intent as? Intent ?: return null
    if (!InternalNavigationToken.matches(i)) return null
    
    // Check extra first
    val idFromExtra = i.getLongExtra("noteId", -1L).takeIf { it != -1L }
    if (idFromExtra != null) return idFromExtra

    // Check deep link. Reminders used host `note` before the widget helper; keep both.
    val data = i.data ?: return null
    if (data.scheme == "notelikeus" && (data.host == "editor" || data.host == "note")) {
        return data.lastPathSegment?.toLongOrNull()
    }
    return null
}

actual fun intentRequestsNewNote(intent: Any?): Boolean {
    val i = intent as? Intent ?: return false
    if (i.action == Intent.ACTION_SEND && i.type == "text/plain") return true
    if (!InternalNavigationToken.matches(i)) return false
    return i.getBooleanExtra("createNote", false)
}

/**
 * Text arriving from another app through the share sheet, clamped to the limits the cloud schema
 * enforces (`notes_title_len`, `notes_content_len`).
 *
 * The sender chooses these strings, and nothing downstream trimmed them: a shared article longer
 * than [NoteBackupImporter.MAX_CONTENT_CHARS] produced a note that saved locally and was then
 * rejected by `apply_note_change` on every sync attempt, permanently and with no way for the user
 * to tell which note was stuck. Backup files — the other untrusted note source — are already held
 * to exactly these caps, so external share text is treated the same way.
 */
actual fun extractSharedText(intent: Any?): Pair<String?, String?>? {
    val i = intent as? Intent ?: return null
    if (i.action != Intent.ACTION_SEND || i.type != "text/plain") return null
    val subject = (i.getStringExtra(Intent.EXTRA_SUBJECT)
        ?: i.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        ?.take(NoteBackupImporter.MAX_FIELD_CHARS)
    val text = (i.getStringExtra(Intent.EXTRA_TEXT)
        ?: i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        ?.take(NoteBackupImporter.MAX_CONTENT_CHARS)
    if (subject.isNullOrBlank() && text.isNullOrBlank()) return null
    return Pair(subject?.takeIf { it.isNotBlank() }, text?.takeIf { it.isNotBlank() })
}

fun Intent.markInternalNavigation(): Intent =
    putExtra(EXTRA_INTERNAL_NAV_TOKEN, InternalNavigationToken.current())

/**
 * Explicit MainActivity launch from a non-Activity context (Glance widget, reminder trampoline).
 *
 * Without [Intent.FLAG_ACTIVITY_NEW_TASK] a Glance [actionStartActivity] can no-op on the
 * launcher. A per-note `notelikeus://editor/{id}` data URI keeps PendingIntents distinct and
 * lets [extractEditorNoteId] still work if extras are dropped.
 */
fun widgetMainActivityIntent(
    context: Context,
    noteId: Long? = null,
    createNote: Boolean = false,
): Intent = Intent().apply {
    setClassName(context, "com.aus.notelikeus.MainActivity")
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    when {
        noteId != null -> {
            data = android.net.Uri.parse("notelikeus://editor/$noteId")
            putExtra("noteId", noteId)
        }
        createNote -> {
            data = android.net.Uri.parse("notelikeus://editor/new")
            putExtra("createNote", true)
        }
    }
    markInternalNavigation()
}
