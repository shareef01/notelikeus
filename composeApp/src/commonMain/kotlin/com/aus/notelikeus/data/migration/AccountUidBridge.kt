package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.sync.NoteSyncStateStore

/**
 * Account-switch guard: two ids are the same cloud account only when they are equal.
 * Guest vs signed-in and User A vs User B always isolate.
 */
class AccountUidBridge(
    private val syncStateStore: NoteSyncStateStore,
) {
    fun accountsMatch(last: String?, current: String): Boolean {
        if (last == null) return true
        return last == current
    }

    fun isSameAccountAsLastMerge(current: String): Boolean =
        accountsMatch(syncStateStore.lastMergedUserId(), current)
}
