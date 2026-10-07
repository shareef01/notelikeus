package com.aus.notelikeus.data.remote

import android.content.Context
import com.aus.notelikeus.data.sync.PendingCloudWipeIntent
import com.aus.notelikeus.data.sync.PendingCloudWipeIntentStore

/**
 * The Android home of accepted destructive wipe requests — F-8.
 *
 * A preferences file of its own, deliberately not the sync-state file: local account isolation clears the
 * sync state and drops the account's rows, and a pending delete request must survive exactly that. It holds
 * target uids and timestamps, never a credential.
 *
 * Requests are keyed by owner: one account's accepted deletion is written and cleared without touching any
 * other account's. The single-record keys from the first version of this store are still read, so a request
 * accepted before this change is not lost.
 */
class AndroidPendingCloudWipeIntentStore(context: Context) : PendingCloudWipeIntentStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): List<PendingCloudWipeIntent> = ownerUids().map { uid ->
        PendingCloudWipeIntent(uid, prefs.getLong(timestampKey(uid), 0L))
    }

    override suspend fun find(ownerUid: String): PendingCloudWipeIntent? =
        read().firstOrNull { it.ownerUid == ownerUid }

    override suspend fun write(intent: PendingCloudWipeIntent) {
        val existing = prefs.getLong(timestampKey(intent.ownerUid), NO_TIMESTAMP)
        val stored = prefs.edit()
            .putStringSet(KEY_OWNER_UIDS, (ownerUids() + intent.ownerUid).toSet())
            // A repeat request keeps the original timestamp: the same obligation, still outstanding.
            .putLong(timestampKey(intent.ownerUid), if (existing >= 0) existing else intent.requestedAtMillis)
            .remove(KEY_LEGACY_OWNER_UID)
            .remove(KEY_LEGACY_REQUESTED_AT)
            .commit()
        // commit(), not apply(): the destructive work that follows is authorized by this record existing,
        // so a write that has not reached disk yet is not a write that happened.
        check(stored) { "pending cloud wipe intent could not be stored" }
    }

    override suspend fun clear(ownerUid: String) {
        val remaining = ownerUids() - ownerUid
        val editor = prefs.edit().remove(timestampKey(ownerUid))
        if (remaining.isEmpty()) {
            editor.remove(KEY_OWNER_UIDS).remove(KEY_LEGACY_OWNER_UID).remove(KEY_LEGACY_REQUESTED_AT)
        } else {
            editor.putStringSet(KEY_OWNER_UIDS, remaining.toSet())
        }
        check(editor.commit()) { "pending cloud wipe intent could not be cleared" }
    }

    /** The recorded owners, including one left behind by the first, single-record version of this store. */
    private fun ownerUids(): List<String> {
        val legacy = prefs.getString(KEY_LEGACY_OWNER_UID, null)?.takeIf { it.isNotBlank() }
        val stored = prefs.getStringSet(KEY_OWNER_UIDS, emptySet()).orEmpty()
        return (stored + listOfNotNull(legacy)).distinct()
    }

    private fun timestampKey(ownerUid: String) = "$KEY_REQUESTED_AT$SEPARATOR$ownerUid"

    private companion object {
        const val PREFS_NAME = "pending_cloud_wipe"
        const val KEY_OWNER_UIDS = "owner_uids"
        const val KEY_REQUESTED_AT = "requested_at"
        const val SEPARATOR = ":"
        const val NO_TIMESTAMP = -1L
        /** The first version's keys, still readable so an already-accepted request survives. */
        const val KEY_LEGACY_OWNER_UID = "owner_uid"
        const val KEY_LEGACY_REQUESTED_AT = "requested_at_legacy"
    }
}
