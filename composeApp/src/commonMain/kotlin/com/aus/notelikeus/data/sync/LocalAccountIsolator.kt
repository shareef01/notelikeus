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
    /**
     * Hands the incoming account whatever a signed-out session staged for it.
     *
     * Injected as a function rather than an `AttachmentSyncService`, because that service already
     * depends on the sync engine this class is wired into; taking the type directly would close a
     * dependency cycle. Defaulted to a no-op so the many constructions that have nothing to adopt
     * — tests, platforms without attachments — stay unchanged.
     */
    private val adoptGuestStagedAttachments: suspend (String) -> Unit = {},
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
            return
        }
        // Not isolating means this account is keeping the notes written before it signed in, and a
        // note is not kept without its pictures. Staged attachment bytes live under an owner
        // namespace, so bytes staged while signed out sit under the guest one; the upload path
        // looks only under the current account and silently finds nothing, leaving the note
        // pointing at a picture that can never arrive.
        //
        // This is deliberately here rather than in each platform's sign-in path: the adoption step
        // existed for months and was called from none of them, which is exactly the failure a
        // per-platform hook invites.
        adoptGuestStagedAttachments(incomingUid)
    }
}
