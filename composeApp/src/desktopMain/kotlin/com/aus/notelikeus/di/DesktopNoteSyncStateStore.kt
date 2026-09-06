package com.aus.notelikeus.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.util.AppLog
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class DesktopNoteSyncStateStore(
    private val dataStore: DataStore<Preferences>
) : NoteSyncStateStore {

    private val json = Json { encodeDefaults = true }

    private val deletedMap: MutableMap<Long, Long>
    private val restoredSet: MutableSet<Long>
    private val pendingAttachmentGcMap: MutableMap<Long, MutableSet<String>>
    private val knownCloudSet: MutableSet<Long>
    private var reconciledAt: Long
    private var mergedUserId: String?

    init {
        val prefs = runBlocking { dataStore.data.firstOrNull() }
        deletedMap = parseDeletedMap(prefs).toMutableMap()
        restoredSet = parseIdSet(prefs, KEY_RESTORED_IDS).toMutableSet()
        pendingAttachmentGcMap = parsePendingGc(prefs).mapValues { it.value.toMutableSet() }.toMutableMap()
        knownCloudSet = parseIdSet(prefs, KEY_KNOWN_CLOUD_IDS).toMutableSet()
        reconciledAt = prefs?.get(KEY_LAST_RECONCILED) ?: 0L
        mergedUserId = prefs?.get(KEY_LAST_MERGED_USER_ID)
    }

    override fun markDeleted(noteId: Long, deletedAt: Long) {
        if (noteId !in deletedMap) {
            deletedMap[noteId] = deletedAt
            persistDeleted()
        }
    }

    override fun mergeDeleted(entries: Map<Long, Long>) {
        var changed = false
        for ((id, deletedAt) in entries) {
            val existing = deletedMap[id]
            if (existing == null || deletedAt < existing) {
                deletedMap[id] = deletedAt
                changed = true
            }
        }
        if (changed) persistDeleted()
    }

    override fun isDeleted(noteId: Long): Boolean = noteId in deletedMap

    override fun deletedIds(): Set<Long> = deletedMap.keys

    override fun deletedAtById(): Map<Long, Long> = deletedMap.toMap()

    override fun pruneExpired(maxAgeMs: Long, now: Long): Set<Long> {
        val pruned = mutableSetOf<Long>()
        val iter = deletedMap.entries.iterator()
        while (iter.hasNext()) {
            val (id, deletedAt) = iter.next()
            if (now - deletedAt >= maxAgeMs) {
                iter.remove()
                pruned.add(id)
            }
        }
        if (pruned.isNotEmpty()) persistDeleted()
        return pruned
    }

    override fun clearDeleted(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        if (deletedMap.keys.removeAll(ids.toSet())) persistDeleted()
    }

    override fun markRestored(noteId: Long) {
        if (restoredSet.add(noteId)) persistRestored()
    }

    override fun restoredIds(): Set<Long> = restoredSet.toSet()

    override fun clearRestored(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        if (restoredSet.removeAll(ids.toSet())) persistRestored()
    }

    override fun markPendingAttachmentGc(noteId: Long, attachmentIds: Collection<String>) {
        pendingAttachmentGcMap.getOrPut(noteId) { mutableSetOf() }.addAll(attachmentIds)
        persistPendingGc()
    }

    override fun pendingAttachmentGcIds(): Set<Long> = pendingAttachmentGcMap.keys.toSet()

    override fun pendingAttachmentGcEntries(): Map<Long, Set<String>> =
        pendingAttachmentGcMap.mapValues { it.value.toSet() }

    override fun clearPendingAttachmentGc(noteId: Long) {
        if (pendingAttachmentGcMap.remove(noteId) != null) persistPendingGc()
    }

    override fun lastReconciledAt(): Long = reconciledAt

    override fun markReconciled(at: Long) {
        reconciledAt = at
        runBlocking { dataStore.edit { it[KEY_LAST_RECONCILED] = at } }
    }

    override fun knownCloudIds(): Set<Long> = knownCloudSet.toSet()

    override fun setKnownCloudIds(ids: Set<Long>) {
        knownCloudSet.clear()
        knownCloudSet.addAll(ids)
        persistKnownCloud()
    }

    override fun lastMergedUserId(): String? = mergedUserId

    override fun setLastMergedUserId(userId: String) {
        mergedUserId = userId
        runBlocking { dataStore.edit { it[KEY_LAST_MERGED_USER_ID] = userId } }
    }

    override fun clear() {
        deletedMap.clear()
        restoredSet.clear()
        pendingAttachmentGcMap.clear()
        knownCloudSet.clear()
        reconciledAt = 0L
        mergedUserId = null
        runBlocking { dataStore.edit { it.clear() } }
    }

    override fun currentTimeMillis(): Long = DateUtils.currentTimeMillis()

    private fun persistDeleted() {
        val encoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()),
            deletedMap.mapKeys { it.key.toString() })
        runBlocking { dataStore.edit { it[KEY_DELETED_JSON] = encoded } }
    }

    private fun persistRestored() {
        val encoded = json.encodeToString(SetSerializer(String.serializer()),
            restoredSet.map { it.toString() }.toSet())
        runBlocking { dataStore.edit { it[KEY_RESTORED_IDS] = encoded } }
    }

    private fun persistPendingGc() {
        val encoded = json.encodeToString(
            MapSerializer(String.serializer(), SetSerializer(String.serializer())),
            pendingAttachmentGcMap.mapKeys { it.key.toString() }.mapValues { it.value.toSet() },
        )
        runBlocking { dataStore.edit { it[KEY_PENDING_ATTACHMENT_GC] = encoded } }
    }

    private fun parsePendingGc(prefs: Preferences?): Map<Long, Set<String>> {
        val raw = prefs?.get(KEY_PENDING_ATTACHMENT_GC) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Set<String>>>(raw)
                .mapKeys { it.key.toLong() }
        } catch (error: Exception) {
            AppLog.warn(TAG, "Pending attachment GC map unreadable; starting with none", error)
            emptyMap()
        }
    }

    private fun persistKnownCloud() {
        val encoded = json.encodeToString(SetSerializer(String.serializer()),
            knownCloudSet.map { it.toString() }.toSet())
        runBlocking { dataStore.edit { it[KEY_KNOWN_CLOUD_IDS] = encoded } }
    }

    // Starting from empty is the only way to keep the app usable, but it is not harmless: losing
    // the tombstone map lets an already-deleted note come back from the cloud, and losing the
    // known-cloud set makes the next sync treat every remote note as new. Log it so a corrupt
    // preferences file is diagnosable instead of showing up later as resurrected notes.
    private fun parseDeletedMap(prefs: Preferences?): Map<Long, Long> {
        val raw = prefs?.get(KEY_DELETED_JSON) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(raw).mapKeys { it.key.toLong() }
        } catch (error: Exception) {
            AppLog.warn(TAG, "Deleted-note tombstones unreadable; starting with none", error)
            emptyMap()
        }
    }

    private fun parseIdSet(prefs: Preferences?, key: Preferences.Key<String>): Set<Long> {
        val raw = prefs?.get(key) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(raw).mapNotNull { it.toLongOrNull() }.toSet()
        } catch (error: Exception) {
            AppLog.warn(TAG, "Sync id set '${key.name}' unreadable; starting with none", error)
            emptySet()
        }
    }

    companion object {
        private const val TAG = "NoteSyncStateStore"
        private val KEY_DELETED_JSON = stringPreferencesKey("sync_deleted_json")
        private val KEY_RESTORED_IDS = stringPreferencesKey("sync_restored_ids")
        private val KEY_PENDING_ATTACHMENT_GC = stringPreferencesKey("sync_pending_attachment_gc")
        private val KEY_KNOWN_CLOUD_IDS = stringPreferencesKey("sync_known_cloud_ids")
        private val KEY_LAST_RECONCILED = longPreferencesKey("sync_last_reconciled")
        private val KEY_LAST_MERGED_USER_ID = stringPreferencesKey("sync_last_merged_user_id")
    }
}
