package com.aus.notelikeus.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import org.json.JSONObject

/**
 * Android SharedPreferences implementation of [NoteSyncStateStore].
 *
 * Tombstone timestamps align with cloud `users/{uid}/tombstones/{id}.deletedAt`.
 */
class SharedPrefsNoteSyncStateStore(
    context: Context
) : NoteSyncStateStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun markDeleted(noteId: Long, deletedAt: Long) {
        val map = deletedAtById().toMutableMap()
        if (noteId !in map) {
            map[noteId] = deletedAt
            writeDeletedMap(map)
        }
    }

    override fun mergeDeleted(entries: Map<Long, Long>) {
        val map = deletedAtById().toMutableMap()
        var changed = false
        for ((id, deletedAt) in entries) {
            val existing = map[id]
            if (existing == null || deletedAt < existing) {
                map[id] = deletedAt
                changed = true
            }
        }
        if (changed) writeDeletedMap(map)
    }

    override fun isDeleted(noteId: Long): Boolean = noteId in deletedAtById()

    override fun deletedIds(): Set<Long> = deletedAtById().keys

    override fun deletedAtById(): Map<Long, Long> {
        val json = prefs.getString(KEY_DELETED_JSON, null)
        if (json.isNullOrBlank()) {
            // Migrate legacy string-set tombstones (no timestamps).
            val legacy = prefs.getStringSet(KEY_DELETED, emptySet()).orEmpty()
            if (legacy.isEmpty()) return emptyMap()
            val now = System.currentTimeMillis()
            val migrated = legacy.mapNotNull { it.toLongOrNull()?.let { id -> id to now } }.toMap()
            writeDeletedMap(migrated)
            prefs.edit().remove(KEY_DELETED).apply()
            return migrated
        }
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val id = key.toLongOrNull() ?: continue
                    put(id, obj.optLong(key, System.currentTimeMillis()))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Removes local tombstones older than [maxAgeMs]; returns pruned ids. */
    override fun pruneExpired(maxAgeMs: Long, now: Long): Set<Long> {
        val map = deletedAtById().toMutableMap()
        val pruned = mutableSetOf<Long>()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val (id, deletedAt) = iterator.next()
            if (now - deletedAt >= maxAgeMs) {
                iterator.remove()
                pruned.add(id)
            }
        }
        if (pruned.isNotEmpty()) writeDeletedMap(map)
        return pruned
    }

    override fun clearDeleted(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val map = deletedAtById().toMutableMap()
        var changed = false
        for (id in ids) {
            if (map.remove(id) != null) changed = true
        }
        if (changed) writeDeletedMap(map)
    }

    /**
     * Notes brought back by an undo of a permanent delete, held until the cloud tombstone is
     * confirmed gone. Without this, a restore whose sync work exhausts its retries leaves the
     * cloud tombstone in place, and the next [mergeDeleted] re-imports it and the note is purged
     * again — so the marker survives process death and every sync re-attempts the cleanup.
     */
    override fun markRestored(noteId: Long) {
        val ids = restoredIds().toMutableSet()
        if (ids.add(noteId)) writeRestored(ids)
    }

    override fun restoredIds(): Set<Long> =
        prefs.getStringSet(KEY_RESTORED, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    override fun clearRestored(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val current = restoredIds().toMutableSet()
        if (current.removeAll(ids.toSet())) writeRestored(current)
    }

    private fun writeRestored(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_RESTORED, ids.map { it.toString() }.toSet()).apply()
    }

    /**
     * High-water mark (note timestamp) up to which the periodic reconciliation worker has already
     * pushed local changes. It lets that worker re-check only notes changed since, instead of
     * reading the whole cloud collection every cycle.
     */
    override fun lastReconciledAt(): Long = prefs.getLong(KEY_LAST_RECONCILED, 0L)

    override fun markReconciled(at: Long) {
        prefs.edit().putLong(KEY_LAST_RECONCILED, at).apply()
    }

    override fun knownCloudIds(): Set<Long> =
        prefs.getStringSet(KEY_KNOWN_CLOUD, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    override fun setKnownCloudIds(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_KNOWN_CLOUD, ids.map { it.toString() }.toSet()).apply()
    }

    override fun lastMergedUserId(): String? =
        prefs.getString(KEY_LAST_MERGED_USER_ID, null)?.takeIf { it.isNotBlank() }

    override fun setLastMergedUserId(userId: String) {
        prefs.edit().putString(KEY_LAST_MERGED_USER_ID, userId).apply()
    }

    override fun linkedFirebaseUid(): String? =
        prefs.getString(KEY_LINKED_FIREBASE_UID, null)?.takeIf { it.isNotBlank() }

    override fun setLinkedFirebaseUid(userId: String?) {
        prefs.edit().apply {
            if (userId.isNullOrBlank()) remove(KEY_LINKED_FIREBASE_UID) else putString(KEY_LINKED_FIREBASE_UID, userId)
        }.apply()
    }

    override fun isFirebaseSupabaseCloudMigrated(): Boolean =
        prefs.getBoolean(KEY_FIREBASE_CLOUD_MIGRATED, false)

    override fun setFirebaseSupabaseCloudMigrated(migrated: Boolean) {
        prefs.edit().putBoolean(KEY_FIREBASE_CLOUD_MIGRATED, migrated).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    private fun writeDeletedMap(map: Map<Long, Long>) {
        val obj = JSONObject()
        for ((id, deletedAt) in map) {
            obj.put(id.toString(), deletedAt)
        }
        prefs.edit().putString(KEY_DELETED_JSON, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "note_sync_state"
        private const val KEY_DELETED = "deleted_ids"
        private const val KEY_DELETED_JSON = "deleted_at_by_id"
        private const val KEY_KNOWN_CLOUD = "known_cloud_ids"
        private const val KEY_RESTORED = "restored_ids"
        private const val KEY_LAST_MERGED_USER_ID = "last_merged_user_id"
        private const val KEY_LINKED_FIREBASE_UID = "linked_firebase_uid"
        private const val KEY_FIREBASE_CLOUD_MIGRATED = "firebase_supabase_cloud_migrated"
        private const val KEY_LAST_RECONCILED = "last_reconciled_at"
    }
}
