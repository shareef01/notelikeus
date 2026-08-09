package com.aus.notelikeus.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The note ids whose cloud writes have not landed yet.
 *
 * An interface so [com.aus.notelikeus.platform.DesktopSyncCoordinator] can be tested against an
 * in-memory double without standing up a DataStore on disk.
 */
interface PendingSyncStore {
    suspend fun load(): DesktopPendingSyncStore.Pending
    suspend fun save(uploads: Set<Long>, deletes: Set<Long>, restores: Set<Long>)
    suspend fun clear()
}

/**
 * Desktop counterpart to Android's `PendingCloudSyncStore`: the pending set, surviving process
 * exit.
 *
 * Desktop previously had no queue at all — every mutation fired an immediate upload and a failed
 * one was simply gone, with `clearPending()` a no-op.
 */
class DesktopPendingSyncStore(
    private val dataStore: DataStore<Preferences>
) : PendingSyncStore {

    private val json = Json { encodeDefaults = true }

    override suspend fun load(): Pending {
        val prefs = dataStore.data.firstOrNull()
        return Pending(
            uploads = read(prefs, KEY_UPLOADS),
            deletes = read(prefs, KEY_DELETES),
            restores = read(prefs, KEY_RESTORES)
        )
    }

    override suspend fun save(uploads: Set<Long>, deletes: Set<Long>, restores: Set<Long>) {
        dataStore.edit { prefs ->
            prefs[KEY_UPLOADS] = encode(uploads)
            prefs[KEY_DELETES] = encode(deletes)
            prefs[KEY_RESTORES] = encode(restores)
        }
    }

    override suspend fun clear() {
        save(emptySet(), emptySet(), emptySet())
    }

    data class Pending(
        val uploads: Set<Long>,
        val deletes: Set<Long>,
        val restores: Set<Long>
    ) {
        val isEmpty: Boolean get() = uploads.isEmpty() && deletes.isEmpty() && restores.isEmpty()
    }

    private fun encode(ids: Set<Long>): String =
        json.encodeToString(SetSerializer(String.serializer()), ids.map { it.toString() }.toSet())

    private fun read(prefs: Preferences?, key: Preferences.Key<String>): Set<Long> {
        val raw = prefs?.get(key) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(raw).mapNotNull { it.toLongOrNull() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private companion object {
        val KEY_UPLOADS = stringPreferencesKey("pending_sync_uploads")
        val KEY_DELETES = stringPreferencesKey("pending_sync_deletes")
        val KEY_RESTORES = stringPreferencesKey("pending_sync_restores")
    }
}
