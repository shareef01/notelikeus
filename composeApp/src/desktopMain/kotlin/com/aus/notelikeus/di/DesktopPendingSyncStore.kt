package com.aus.notelikeus.di

import com.aus.notelikeus.domain.platform.PendingSyncKind
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aus.notelikeus.data.sync.DatasetEpoch
import com.aus.notelikeus.data.sync.DatasetEpochStore
import com.aus.notelikeus.data.sync.DatasetPending
import com.aus.notelikeus.data.sync.decodePendingSyncCommand
import com.aus.notelikeus.data.sync.encodeForStorage
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The note ids whose cloud writes have not landed yet, together with the dataset epoch they belong
 * to.
 *
 * Desktop counterpart to Android's `PendingCloudSyncStore`. The queue and the epoch are written by a
 * single DataStore `edit { }` — DataStore applies one edit atomically — because a rotation must not
 * be able to leave a new epoch beside the previous dataset's commands.
 *
 * Desktop previously had no queue at all: every mutation fired an immediate upload and a failed one
 * was simply gone, with `clearPending()` a no-op.
 */
class DesktopPendingSyncStore(
    private val dataStore: DataStore<Preferences>
) : DatasetEpochStore {

    private val json = Json { encodeDefaults = true }

    override suspend fun load(): DatasetPending {
        val prefs = dataStore.data.firstOrNull()
        val epoch = prefs?.get(KEY_EPOCH)?.takeIf { it.isNotBlank() }?.let(::DatasetEpoch)
        val commands = PendingSyncKind.entries.flatMap { kind ->
            read(prefs, keyFor(kind)).mapNotNull(::decodePendingSyncCommand)
                .filter { it.kind == kind }
        }
        return DatasetPending(
            epoch = epoch,
            ownerUid = prefs?.get(KEY_OWNER_UID)?.takeIf { it.isNotBlank() },
            commands = commands,
            // An in-flight isolation is part of the same record, so a crash cannot separate "the
            // device is between datasets" from the queue that state has to refuse.
            isolationTarget = prefs?.get(KEY_ISOLATION_TARGET)
                ?.takeIf { it.isNotBlank() }
                ?.let(::DatasetEpoch),
        )
    }

    /**
     * One DataStore `edit { }` for the whole record — epoch, owner, isolation marker and every queue.
     *
     * **Failure surface, precisely.** `edit` is a `suspend` call, and DataStore's own contract is about
     * the *write* being atomic, not about what a failure the caller observes proves: the durable file
     * swap can have happened and the call can still surface a `CancellationException` or an I/O
     * failure afterwards. So a throw from here does **not** establish that the record is unchanged.
     * Callers that need to know must read it back — `DatasetEpochAuthority.beginIsolation` does exactly
     * that when its marker write fails.
     */
    override suspend fun save(pending: DatasetPending) {
        dataStore.edit { prefs ->
            prefs[KEY_EPOCH] = pending.epoch?.value.orEmpty()
            prefs[KEY_OWNER_UID] = pending.ownerUid.orEmpty()
            prefs[KEY_ISOLATION_TARGET] = pending.isolationTarget?.value.orEmpty()
            for (kind in PendingSyncKind.entries) {
                prefs[keyFor(kind)] = encode(pending.commandsOf(kind).map { it.encodeForStorage() })
            }
        }
    }

    private fun keyFor(kind: PendingSyncKind): Preferences.Key<String> = when (kind) {
        PendingSyncKind.UPLOAD -> KEY_UPLOADS
        PendingSyncKind.DELETE -> KEY_DELETES
        PendingSyncKind.RESTORE -> KEY_RESTORES
    }

    private fun encode(entries: List<String>): String =
        json.encodeToString(SetSerializer(String.serializer()), entries.toSet())

    /** Reads raw stored entries. They are decoded — and possibly dropped — by [load]. */
    private fun read(prefs: Preferences?, key: Preferences.Key<String>): Set<String> {
        val raw = prefs?.get(key) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(raw)
        } catch (error: Exception) {
            // Dropping the queue silently is what the store exists to prevent: those ids are
            // edits that never reached the cloud, and nothing else records them.
            AppLog.warn(TAG, "Pending sync set '${key.name}' unreadable; those writes are lost", error)
            emptySet()
        }
    }

    private companion object {
        const val TAG = "PendingSyncStore"
        val KEY_UPLOADS = stringPreferencesKey("pending_sync_uploads")
        val KEY_DELETES = stringPreferencesKey("pending_sync_deletes")
        val KEY_RESTORES = stringPreferencesKey("pending_sync_restores")
        val KEY_EPOCH = stringPreferencesKey("pending_sync_dataset_epoch")
        val KEY_OWNER_UID = stringPreferencesKey("pending_sync_dataset_owner_uid")

        /** Non-blank while an isolation is in flight — see `DatasetPending.isolationTarget`. */
        val KEY_ISOLATION_TARGET = stringPreferencesKey("pending_sync_dataset_isolation_target")
    }
}
