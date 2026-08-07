package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AndroidSyncManager(
    private val sessionManager: FirebaseSessionManager,
    private val syncEngine: NoteSyncEngine
) : SyncManager {
    private val _syncStatus = MutableStateFlow<CloudSyncStatus>(CloudSyncStatus.Unknown)
    override val syncStatus: StateFlow<CloudSyncStatus> = _syncStatus.asStateFlow()

    private val _cloudAccount = MutableStateFlow(CloudAccount())
    override val cloudAccount: StateFlow<CloudAccount> = _cloudAccount.asStateFlow()

    init {
        val account = sessionManager.getCurrentAccount()
        _cloudAccount.update {
            CloudAccount(
                email = account.email,
                isGoogleAccount = account.isGoogleAccount,
                isAnonymous = account.isAnonymous
            )
        }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return sessionManager.signInWithGoogle(idToken).onSuccess {
            refreshAccount()
        }
    }

    override suspend fun signInWithEmail(email: String, password: String, create: Boolean): Result<Unit> {
        return sessionManager.signInWithEmailPassword(email, password, create).onSuccess {
            refreshAccount()
        }
    }

    private fun refreshAccount() {
        val account = sessionManager.getCurrentAccount()
        _cloudAccount.update {
            CloudAccount(
                email = account.email,
                isGoogleAccount = account.isGoogleAccount,
                isAnonymous = account.isAnonymous
            )
        }
    }

    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
        if (deleteCloudData) {
            syncEngine.deleteAllCloudData()
        }
        return sessionManager.signOut().onSuccess {
            refreshAccount()
        }
    }

    override suspend fun syncNotes() {
        syncEngine.uploadAllNotes()
    }

    override suspend fun downloadNotes() {
        syncEngine.downloadAllNotes()
    }

    override fun clearPendingEvent() {
    }
}
