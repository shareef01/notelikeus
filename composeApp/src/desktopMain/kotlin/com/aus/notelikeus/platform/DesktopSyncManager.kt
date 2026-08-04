package com.aus.notelikeus.platform

import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopSyncManager : SyncManager {
    override val syncStatus: StateFlow<CloudSyncStatus> = MutableStateFlow(CloudSyncStatus.Unknown).asStateFlow()
    override val cloudAccount: StateFlow<CloudAccount> = MutableStateFlow(CloudAccount(isGoogleAccount = true)).asStateFlow() // Bypass sign-in for now

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithEmail(email: String, password: String, create: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun syncNotes() {}
    override suspend fun downloadNotes() {}
    override fun clearPendingEvent() {}
}
