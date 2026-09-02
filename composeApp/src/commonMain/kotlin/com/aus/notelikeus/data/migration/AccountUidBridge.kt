package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.sync.NoteSyncStateStore

object AccountUidPatterns {
    private val SUPABASE_UUID_REGEX =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    fun isSupabaseUuid(value: String): Boolean = SUPABASE_UUID_REGEX.matches(value)

    fun isLikelyFirebaseUid(value: String): Boolean =
        value.isNotBlank() && !isSupabaseUuid(value)
}

/**
 * Links legacy Firebase Auth UIDs to Supabase UUIDs so account-switch guards treat them as one user.
 */
class AccountUidBridge(
    private val syncStateStore: NoteSyncStateStore,
) {
    fun linkedFirebaseUid(): String? = syncStateStore.linkedFirebaseUid()

    fun linkAccounts(firebaseUid: String, supabaseUid: String) {
        syncStateStore.setLinkedFirebaseUid(firebaseUid)
        val last = syncStateStore.lastMergedUserId()
        if (last == null || last == firebaseUid || accountsMatch(last, supabaseUid)) {
            syncStateStore.setLastMergedUserId(supabaseUid)
        }
    }

    fun accountsMatch(last: String?, current: String): Boolean {
        if (last == null) return true
        if (last == current) return true
        val linked = linkedFirebaseUid() ?: return false
        return (last == linked && AccountUidPatterns.isSupabaseUuid(current)) ||
            (current == linked && AccountUidPatterns.isSupabaseUuid(last))
    }

    fun isSameAccountAsLastMerge(current: String): Boolean =
        accountsMatch(syncStateStore.lastMergedUserId(), current)
}
