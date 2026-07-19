package com.aus.notelikeus.data.remote

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Survives process death for the debounced cloud sync queue. */
@Singleton
class PendingCloudSyncStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pendingUploads(): Set<Long> = readIds(KEY_UPLOADS)

    fun pendingDeletes(): Set<Long> = readIds(KEY_DELETES)

    fun save(uploads: Set<Long>, deletes: Set<Long>) {
        prefs.edit()
            .putStringSet(KEY_UPLOADS, uploads.map { it.toString() }.toSet())
            .putStringSet(KEY_DELETES, deletes.map { it.toString() }.toSet())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun readIds(key: String): Set<Long> =
        prefs.getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    companion object {
        private const val PREFS_NAME = "pending_cloud_sync"
        private const val KEY_UPLOADS = "uploads"
        private const val KEY_DELETES = "deletes"
    }
}
