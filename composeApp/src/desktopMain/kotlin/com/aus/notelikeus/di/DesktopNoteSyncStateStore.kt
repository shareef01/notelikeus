package com.aus.notelikeus.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/**
 * Every member is `@Synchronized`: the maps below are plain collections, and they are reached from
 * more than one thread at once -- the sync manager's worker dispatcher, DesktopSyncCoordinator's
 * own scope, and the UI thread (a note delete records its digest from there). Unguarded, a write
 * that lands while another thread is encoding the same map throws ConcurrentModificationException
 * or drops an entry.
 *
 * A write returns only once DataStore has it on disk, so callers must not be on the UI thread for
 * anything that writes in a loop; DesktopSyncManager moves sync work off it for that reason.
 */
class DesktopNoteSyncStateStore(
    private val dataStore: DataStore<Preferences>
) : NoteSyncStateStore {

    private val json = Json { encodeDefaults = true }

    private val deletedMap: MutableMap<Long, Long>
    private val restoredSet: MutableSet<Long>
    private val recommitNeededSet: MutableSet<Long>
    private val pendingAttachmentGcMap: MutableMap<Long, MutableSet<String>>
    private val knownCloudSet: MutableSet<Long>
    private val knownServerRevisionsMap: MutableMap<Long, Long>
    private val baselineDeleteRevisionsMap: MutableMap<Long, Long>
    private val syncedTimestampsMap: MutableMap<Long, Long>
    private val deletedDigestsMap: MutableMap<Long, String>
    private var reconciledAt: Long
    private var mergedUserId: String?
    private var wipeGenerationValue: Long
    private var wipeUploadsBlockedValue: Boolean

    init {
        val prefs = runBlocking { dataStore.data.firstOrNull() }
        deletedMap = parseDeletedMap(prefs).toMutableMap()
        restoredSet = parseIdSet(prefs, KEY_RESTORED_IDS).toMutableSet()
        recommitNeededSet = parseIdSet(prefs, KEY_RECOMMIT_NEEDED_IDS).toMutableSet()
        pendingAttachmentGcMap = parsePendingGc(prefs).mapValues { it.value.toMutableSet() }.toMutableMap()
        knownCloudSet = parseIdSet(prefs, KEY_KNOWN_CLOUD_IDS).toMutableSet()
        knownServerRevisionsMap = parseLongMap(prefs, KEY_KNOWN_SERVER_REVISIONS).toMutableMap()
        baselineDeleteRevisionsMap = parseLongMap(prefs, KEY_BASELINE_DELETE_REVISIONS).toMutableMap()
        syncedTimestampsMap = parseLongMap(prefs, KEY_SYNCED_TIMESTAMPS).toMutableMap()
        deletedDigestsMap = prefs?.get(KEY_DELETED_DIGESTS)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw).mapKeys { it.key.toLong() } }
                .getOrElse { error ->
                    AppLog.warn(TAG, "Delete fingerprints unreadable; starting with none", error)
                    emptyMap()
                }
        }.orEmpty().toMutableMap()
        reconciledAt = prefs?.get(KEY_LAST_RECONCILED) ?: 0L
        mergedUserId = prefs?.get(KEY_LAST_MERGED_USER_ID)
        wipeGenerationValue = prefs?.get(KEY_WIPE_GENERATION) ?: 0L
        wipeUploadsBlockedValue = prefs?.get(KEY_WIPE_UPLOADS_BLOCKED) ?: false
    }

    @Synchronized
    override fun markDeleted(noteId: Long, deletedAt: Long, baselineRevision: Long?) {
        var changed = false
        if (noteId !in deletedMap) {
            deletedMap[noteId] = deletedAt
            changed = true
        }
        if (baselineRevision != null) {
            baselineDeleteRevisionsMap[noteId] = baselineRevision
            changed = true
        }
        if (changed) persistDeletedAndBaseline()
    }

    private fun persistDeletedAndBaseline() = runBlocking {
        val deletedEncoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), deletedMap.mapKeys { it.key.toString() })
        val baselineEncoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), baselineDeleteRevisionsMap.mapKeys { it.key.toString() })
        dataStore.edit { prefs ->
            prefs[KEY_DELETED_JSON] = deletedEncoded
            prefs[KEY_BASELINE_DELETE_REVISIONS] = baselineEncoded
        }
    }

    @Synchronized
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

    @Synchronized
    override fun isDeleted(noteId: Long): Boolean = noteId in deletedMap

    @Synchronized
    override fun deletedIds(): Set<Long> = deletedMap.keys.toSet()

    @Synchronized
    override fun deletedAtById(): Map<Long, Long> = deletedMap.toMap()

    @Synchronized
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

    @Synchronized
    override fun clearDeleted(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        if (deletedMap.keys.removeAll(ids.toSet())) persistDeleted()
    }

    @Synchronized
    override fun markRestored(noteId: Long) {
        if (restoredSet.add(noteId)) persistRestored()
    }

    @Synchronized
    override fun restoredIds(): Set<Long> = restoredSet.toSet()

    @Synchronized
    override fun clearRestored(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        if (restoredSet.removeAll(ids.toSet())) persistRestored()
    }

    @Synchronized
    override fun markRecommitNeeded(noteId: Long) {
        if (recommitNeededSet.add(noteId)) persistRecommitNeeded()
    }

    @Synchronized
    override fun recommitNeededIds(): Set<Long> = recommitNeededSet.toSet()

    @Synchronized
    override fun clearRecommitNeeded(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        if (recommitNeededSet.removeAll(ids.toSet())) persistRecommitNeeded()
    }

    @Synchronized
    override fun markPendingAttachmentGc(noteId: Long, attachmentIds: Collection<String>) {
        pendingAttachmentGcMap.getOrPut(noteId) { mutableSetOf() }.addAll(attachmentIds)
        persistPendingGc()
    }

    @Synchronized
    override fun pendingAttachmentGcIds(): Set<Long> = pendingAttachmentGcMap.keys.toSet()

    @Synchronized
    override fun pendingAttachmentGcEntries(): Map<Long, Set<String>> =
        pendingAttachmentGcMap.mapValues { it.value.toSet() }

    @Synchronized
    override fun clearPendingAttachmentGc(noteId: Long) {
        if (pendingAttachmentGcMap.remove(noteId) != null) persistPendingGc()
    }

    @Synchronized
    override fun lastReconciledAt(): Long = reconciledAt

    @Synchronized
    override fun markReconciled(at: Long) {
        reconciledAt = at
        runBlocking { dataStore.edit { it[KEY_LAST_RECONCILED] = at } }
    }

    @Synchronized
    override fun knownCloudIds(): Set<Long> = knownCloudSet.toSet()

    @Synchronized
    override fun setKnownCloudIds(ids: Set<Long>) {
        knownCloudSet.clear()
        knownCloudSet.addAll(ids)
        persistKnownCloud()
    }

    @Synchronized
    override fun knownServerRevisionById(): Map<Long, Long> = knownServerRevisionsMap.toMap()

    @Synchronized
    override fun updateKnownServerRevision(noteId: Long, revision: Long) {
        knownServerRevisionsMap[noteId] = revision
        persistKnownServerRevisions()
    }

    @Synchronized
    override fun updateKnownServerRevisions(revisions: Map<Long, Long>) {
        knownServerRevisionsMap.putAll(revisions)
        persistKnownServerRevisions()
    }

    @Synchronized
    override fun clearKnownServerRevisions(noteIds: Collection<Long>) {
        if (noteIds.isEmpty()) return
        var changed = false
        for (id in noteIds) {
            if (knownServerRevisionsMap.remove(id) != null) changed = true
        }
        if (changed) persistKnownServerRevisions()
    }

    @Synchronized
    override fun syncedTimestampById(): Map<Long, Long> = syncedTimestampsMap.toMap()

    @Synchronized
    override fun updateSyncedTimestamps(timestamps: Map<Long, Long>) {
        if (timestamps.isEmpty()) return
        syncedTimestampsMap.putAll(timestamps)
        persistSyncedTimestamps()
    }

    @Synchronized
    override fun clearSyncedTimestamps(noteIds: Collection<Long>) {
        if (noteIds.isEmpty()) return
        var changed = false
        for (id in noteIds) {
            if (syncedTimestampsMap.remove(id) != null) changed = true
        }
        if (changed) persistSyncedTimestamps()
    }

    private fun persistSyncedTimestamps() {
        val encoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()),
            syncedTimestampsMap.mapKeys { it.key.toString() })
        runBlocking { dataStore.edit { it[KEY_SYNCED_TIMESTAMPS] = encoded } }
    }

    @Synchronized
    override fun deletedDigestById(): Map<Long, String> = deletedDigestsMap.toMap()

    @Synchronized
    override fun setDeletedDigest(noteId: Long, digest: String) {
        deletedDigestsMap[noteId] = digest
        persistDeletedDigests()
    }

    @Synchronized
    override fun clearDeletedDigests(noteIds: Collection<Long>) {
        var changed = false
        for (id in noteIds) {
            if (deletedDigestsMap.remove(id) != null) changed = true
        }
        if (changed) persistDeletedDigests()
    }

    private fun persistDeletedDigests() {
        val encoded = json.encodeToString(MapSerializer(String.serializer(), String.serializer()),
            deletedDigestsMap.mapKeys { it.key.toString() })
        runBlocking { dataStore.edit { it[KEY_DELETED_DIGESTS] = encoded } }
    }

    @Synchronized
    override fun baselineDeleteRevisionById(): Map<Long, Long> = baselineDeleteRevisionsMap.toMap()

    @Synchronized
    override fun setBaselineDeleteRevision(noteId: Long, revision: Long) {
        baselineDeleteRevisionsMap[noteId] = revision
        persistBaselineDeleteRevisions()
    }

    @Synchronized
    override fun clearBaselineDeleteRevision(noteId: Long) {
        if (baselineDeleteRevisionsMap.remove(noteId) != null) persistBaselineDeleteRevisions()
    }

    @Synchronized
    override fun lastMergedUserId(): String? = mergedUserId

    @Synchronized
    override fun setLastMergedUserId(userId: String) {
        mergedUserId = userId
        runBlocking { dataStore.edit { it[KEY_LAST_MERGED_USER_ID] = userId } }
    }

    @Synchronized
    override fun wipeGeneration(): Long = wipeGenerationValue

    @Synchronized
    override fun setWipeGeneration(generation: Long) {
        wipeGenerationValue = generation
        runBlocking { dataStore.edit { it[KEY_WIPE_GENERATION] = generation } }
    }

    @Synchronized
    override fun uploadsBlockedAfterCloudWipe(): Boolean = wipeUploadsBlockedValue

    @Synchronized
    override fun blockUploadsAfterCloudWipe() {
        wipeUploadsBlockedValue = true
        runBlocking { dataStore.edit { it[KEY_WIPE_UPLOADS_BLOCKED] = true } }
    }

    @Synchronized
    override fun allowUploadsAfterCloudWipe() {
        wipeUploadsBlockedValue = false
        runBlocking { dataStore.edit { it[KEY_WIPE_UPLOADS_BLOCKED] = false } }
    }

    @Synchronized
    override fun clear() {
        deletedMap.clear()
        restoredSet.clear()
        recommitNeededSet.clear()
        pendingAttachmentGcMap.clear()
        knownCloudSet.clear()
        knownServerRevisionsMap.clear()
        baselineDeleteRevisionsMap.clear()
        syncedTimestampsMap.clear()
        deletedDigestsMap.clear()
        reconciledAt = 0L
        mergedUserId = null
        wipeGenerationValue = 0L
        wipeUploadsBlockedValue = false
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

    private fun persistRecommitNeeded() {
        val encoded = json.encodeToString(SetSerializer(String.serializer()),
            recommitNeededSet.map { it.toString() }.toSet())
        runBlocking { dataStore.edit { it[KEY_RECOMMIT_NEEDED_IDS] = encoded } }
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

    private fun persistKnownServerRevisions() {
        val encoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()),
            knownServerRevisionsMap.mapKeys { it.key.toString() })
        runBlocking { dataStore.edit { it[KEY_KNOWN_SERVER_REVISIONS] = encoded } }
    }

    private fun persistBaselineDeleteRevisions() {
        val encoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()),
            baselineDeleteRevisionsMap.mapKeys { it.key.toString() })
        runBlocking { dataStore.edit { it[KEY_BASELINE_DELETE_REVISIONS] = encoded } }
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

    private fun parseLongMap(prefs: Preferences?, key: Preferences.Key<String>): Map<Long, Long> {
        val raw = prefs?.get(key) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(raw).mapKeys { it.key.toLong() }
        } catch (error: Exception) {
            AppLog.warn(TAG, "Sync long map '${key.name}' unreadable; starting with none", error)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "NoteSyncStateStore"
        private val KEY_DELETED_JSON = stringPreferencesKey("sync_deleted_json")
        private val KEY_RESTORED_IDS = stringPreferencesKey("sync_restored_ids")
        private val KEY_RECOMMIT_NEEDED_IDS = stringPreferencesKey("sync_recommit_needed_ids")
        private val KEY_PENDING_ATTACHMENT_GC = stringPreferencesKey("sync_pending_attachment_gc")
        private val KEY_KNOWN_CLOUD_IDS = stringPreferencesKey("sync_known_cloud_ids")
        private val KEY_KNOWN_SERVER_REVISIONS = stringPreferencesKey("sync_known_server_revisions")
        private val KEY_BASELINE_DELETE_REVISIONS = stringPreferencesKey("sync_baseline_delete_revisions")
        private val KEY_SYNCED_TIMESTAMPS = stringPreferencesKey("sync_synced_timestamps")
        private val KEY_DELETED_DIGESTS = stringPreferencesKey("sync_deleted_digests")
        private val KEY_LAST_RECONCILED = longPreferencesKey("sync_last_reconciled")
        private val KEY_LAST_MERGED_USER_ID = stringPreferencesKey("sync_last_merged_user_id")
        private val KEY_WIPE_GENERATION = longPreferencesKey("sync_wipe_generation")
        private val KEY_WIPE_UPLOADS_BLOCKED = booleanPreferencesKey("sync_wipe_uploads_blocked")
    }
}
