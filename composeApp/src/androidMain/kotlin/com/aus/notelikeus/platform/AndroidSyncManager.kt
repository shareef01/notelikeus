package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.runTimedSync
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncEvent
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

    private val _pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)
    override val pendingEvent: StateFlow<CloudSyncEvent?> = _pendingEvent.asStateFlow()

    init {
        refreshAccount()
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

    /**
     * Signing out destroys the credential [NoteSyncEngine.deleteAllCloudData] needs, so a failure
     * there is permanent once the session is gone: the notes stay in Firestore and nothing can
     * retry. This used to discard the Result and sign out anyway, which reported success for a
     * delete that never happened — and offline or on an expired token is exactly when a user
     * reaches for "sign out and delete". Fail the sign-out instead and leave the session intact so
     * the request can be made again.
     */
    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
        if (deleteCloudData) {
            syncEngine.deleteAllCloudData().onFailure { return Result.failure(it) }
        }
        return sessionManager.signOut().onSuccess {
            refreshAccount()
        }
    }

    override suspend fun syncNotes() {
        runSync({ CloudSyncEvent.Uploaded(it) }) { syncEngine.uploadAllNotes() }
    }

    override suspend fun downloadNotes() {
        runSync({ CloudSyncEvent.Downloaded(it) }) { syncEngine.downloadAllNotes() }
    }

    private suspend fun runSync(
        onSuccess: (Int) -> CloudSyncEvent,
        block: suspend () -> Result<Int>
    ) = runTimedSync(
        status = _syncStatus,
        pendingEvent = _pendingEvent,
        describeError = { sessionManager.diagnose(it) },
        successEvent = onSuccess,
        block = block
    )

    override fun clearPendingEvent() {
        _pendingEvent.value = null
    }
}
