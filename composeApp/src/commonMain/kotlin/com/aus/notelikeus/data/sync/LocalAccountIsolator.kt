package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.NoteRepository

/**
 * Wipes device-local notes, tombstones, and pending cloud work so a new Google account cannot
 * inherit the previous one's library — or apply its leftover tombstones to colliding ids.
 *
 * Sign-out always isolates (privacy policy: the next person at this device must not see the
 * previous account's notes). Sign-in and pull isolate only when [NoteSyncStateStore.lastMergedUserId]
 * is set and differs from the incoming uid, so a first-ever sign-in still uploads guest notes.
 */
class LocalAccountIsolator(
    private val noteRepository: NoteRepository,
    private val syncStateStore: NoteSyncStateStore,
    private val syncCoordinator: SyncCoordinator,
    private val accountUidBridge: AccountUidBridge = AccountUidBridge(syncStateStore),
) {
    suspend fun isolate() {
        // Cancel in-flight workers before dropping rows they would otherwise upload as the new uid.
        syncCoordinator.clearPending()
        syncStateStore.clear()
        noteRepository.clearAllUserData()
    }

    suspend fun isolateIfAccountChanged(incomingUid: String) {
        val last = syncStateStore.lastMergedUserId()
        if (last != null && !accountUidBridge.accountsMatch(last, incomingUid)) {
            isolate()
        }
    }
}
