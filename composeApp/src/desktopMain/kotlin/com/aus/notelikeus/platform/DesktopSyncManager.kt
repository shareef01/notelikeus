package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Desktop [SyncManager] backed by [NoteSyncEngine] with the Firestore REST transport.
 *
 * The engine needs a uid provider that returns the current Firebase ID token's user id.
 * We store the token from sign-in and extract the uid from the decoded JWT (base64 payload).
 */
class DesktopSyncManager(
    private val syncEngine: NoteSyncEngine,
    private val tokenStore: DesktopTokenStore
) : SyncManager {

    private val _syncStatus = MutableStateFlow(CloudSyncStatus.Offline)
    override val syncStatus: StateFlow<CloudSyncStatus> = _syncStatus.asStateFlow()

    private val _cloudAccount = MutableStateFlow(
        if (tokenStore.hasSession()) CloudAccount(
            email = tokenStore.email(),
            isGoogleAccount = true,
            isAnonymous = false
        ) else CloudAccount(isOfflineMode = true)
    )
    override val cloudAccount: StateFlow<CloudAccount> = _cloudAccount.asStateFlow()

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        // Store the token and extract uid/email from the JWT
        tokenStore.save(idToken)
        refreshAccount()
        return Result.success(Unit)
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
        create: Boolean
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Email/password sign-in is not available on desktop")
    )

    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
        if (deleteCloudData) {
            syncEngine.deleteAllCloudData()
        }
        tokenStore.clear()
        _syncStatus.value = CloudSyncStatus.Offline
        _cloudAccount.value = CloudAccount(isOfflineMode = true)
        return Result.success(Unit)
    }

    override suspend fun syncNotes() {
        _syncStatus.value = CloudSyncStatus.Syncing
        syncEngine.uploadAllNotes()
        _syncStatus.value = CloudSyncStatus.Synced
    }

    override suspend fun downloadNotes() {
        _syncStatus.value = CloudSyncStatus.Syncing
        syncEngine.downloadAllNotes()
        _syncStatus.value = CloudSyncStatus.Synced
    }

    override fun clearPendingEvent() {}

    private fun refreshAccount() {
        _cloudAccount.update {
            CloudAccount(
                email = tokenStore.email(),
                isGoogleAccount = true,
                isAnonymous = false
            )
        }
    }
}
