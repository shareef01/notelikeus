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


    suspend fun signInWithGoogle(idToken: String): Result<Unit>
    suspend fun signInWithEmail(email: String, password: String, create: Boolean): Result<Unit>
    suspend fun signOut(deleteCloudData: Boolean): Result<Unit>
    suspend fun syncNotes()
    suspend fun downloadNotes()
    fun clearPendingEvent()
}
