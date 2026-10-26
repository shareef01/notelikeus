package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.ui.main.CloudSyncStatus
import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.CloudAccount
import kotlinx.coroutines.flow.StateFlow

interface SyncManager {
    val syncStatus: StateFlow<CloudSyncStatus>
    val cloudAccount: StateFlow<CloudAccount>

    /**
     * The last sync outcome worth showing the user, or null once acknowledged.
     *
     * [syncNotes] and [downloadNotes] return Unit because callers fire them and move on, so this
     * is how a failure reaches the UI. Without it both implementations discarded their Results
     * and a broken cloud path still displayed as healthy.
     */
    val pendingEvent: StateFlow<CloudSyncEvent?>

    /** Emails a one-time sign-in code to [email]. */
    suspend fun requestEmailCode(email: String): Result<Unit>

    /** Signs in with the emailed code, then runs everything that follows a sign-in. */
    suspend fun signInWithEmailCode(email: String, code: String): Result<Unit>
    suspend fun signInWithEmail(email: String, password: String, create: Boolean): Result<Unit>
    suspend fun signOut(deleteCloudData: Boolean): Result<Unit>
    suspend fun syncNotes()
    suspend fun downloadNotes()

    /**
     * The user's answer to "your cloud notes were deleted from another device": put this device's
     * notes back.
     *
     * A wipe deliberately does not upload the local copies — the server cannot tell that apart from
     * a stale device undoing the wipe — so the decision has to come from here, and this is the only
     * caller of the engine's cloud-wipe override. Implementations upload immediately after lifting
     * the block, because that is what the user just asked for.
     */
    suspend fun allowUploadAfterCloudWipe(): Result<Unit>

    fun clearPendingEvent()
}
