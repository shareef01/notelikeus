package com.aus.notelikeus.data.remote

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local delete tombstones + known cloud note IDs for sync.
 * Tombstone timestamps align with cloud `users/{uid}/tombstones/{id}.deletedAt`.
 */
@Singleton
class NoteSyncStateStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markDeleted(noteId: Long, deletedAt: Long = System.currentTimeMillis()) {
        val map = deletedAtById().toMutableMap()
        if (noteId !in map) {
            map[noteId] = deletedAt
            writeDeletedMap(map)
        }
    }

    fun mergeDeleted(entries: Map<Long, Long>) {
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

    fun isDeleted(noteId: Long): Boolean = noteId in deletedAtById()

    fun deletedIds(): Set<Long> = deletedAtById().keys

    fun deletedAtById(): Map<Long, Long> {
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
    fun pruneExpired(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Set<Long> {
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

    fun clearDeleted(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val map = deletedAtById().toMutableMap()
        var changed = false
        for (id in ids) {
            if (map.remove(id) != null) changed = true
        }
        if (changed) writeDeletedMap(map)
    }

    fun knownCloudIds(): Set<Long> =
        prefs.getStringSet(KEY_KNOWN_CLOUD, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun setKnownCloudIds(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_KNOWN_CLOUD, ids.map { it.toString() }.toSet()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

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
        const val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
