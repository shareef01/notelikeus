package com.aus.notelikeus.data.local

import android.content.Context
import java.io.File

/**
 * Records that [PlaintextDatabaseMigrator] moved a database aside.
 *
 * Quarantining is the right call — the bytes stay recoverable rather than being deleted — but on
 * its own it is silent: Room creates a fresh database and the user opens the app to find every
 * note gone, with nothing saying the old data still exists on disk. This marker is what lets the
 * UI say so. It survives the process because the migration runs during DI, long before any UI.
 */
object DatabaseRecoveryNotice {

    private const val NOTICE_FILE = "db_quarantine_notice"

    internal fun record(context: Context, suffix: Long) {
        runCatching { File(context.filesDir, NOTICE_FILE).writeText(suffix.toString()) }
    }

    /** The quarantine timestamp if one is pending, else null. Does not clear it. */
    fun pending(context: Context): Long? = runCatching {
        val file = File(context.filesDir, NOTICE_FILE)
        if (file.exists()) file.readText().trim().toLongOrNull() else null
    }.getOrNull()

    /** Clears the marker once the user has been shown it. */
    fun consume(context: Context) {
        runCatching { File(context.filesDir, NOTICE_FILE).delete() }
    }
}
