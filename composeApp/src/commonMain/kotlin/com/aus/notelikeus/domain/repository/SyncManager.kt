package com.aus.notelikeus.domain.repository

import com.aus.notelikeus.ui.main.CloudSyncStatus
import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.CloudAccount
import kotlinx.coroutines.flow.StateFlow

interface SyncManager {
    val syncStatus: StateFlow<CloudSyncStatus>
    val cloudAccount: StateFlow<CloudAccount>
    
    suspend fun signInWithGoogle(idToken: String): Result<Unit>
    suspend fun signInWithEmail(email: String, password: String, create: Boolean): Result<Unit>
    suspend fun signOut(deleteCloudData: Boolean): Result<Unit>
    suspend fun syncNotes()
    suspend fun downloadNotes()
    fun clearPendingEvent()
}
