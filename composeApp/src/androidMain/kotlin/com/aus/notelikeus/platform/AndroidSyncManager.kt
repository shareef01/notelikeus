package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * A sync that never returns must not strand the UI.
     *
     * [runSync] flips the status to [CloudSyncStatus.Syncing] and only leaves it on the result, but
     * nothing in the transport bounded how long that could take. Observed on a real device: with
     * the network off, the status stayed Syncing indefinitely, and because ProfileSheet gates its
     * sync controls on `cloudSyncStatus != Syncing`, "Sync to cloud" and "Restore from cloud" were
     * disabled for as long as it hung — so the one screen that could retry had no working button
     * on it. Only reconnecting released it.
     *
     * Generous on purpose: a first full sync of a large library is slow, and cutting a working sync
     * short would be worse than the hang. This is the backstop, not a deadline.
     */
    private val syncTimeoutMs = 90_000L

    private suspend fun runSync(
        onSuccess: (Int) -> CloudSyncEvent,
        block: suspend () -> Result<Int>
    ) {
        _syncStatus.value = CloudSyncStatus.Syncing
        val result = withTimeoutOrNull(syncTimeoutMs) { block() }
        if (result == null) {
            _syncStatus.value = CloudSyncStatus.Error
            _pendingEvent.value = CloudSyncEvent.Failure(SYNC_TIMED_OUT)
            return
        }
        result
            .onSuccess { count ->
                _syncStatus.value = CloudSyncStatus.Synced
                _pendingEvent.value = onSuccess(count)
            }
            .onFailure { error ->
                _syncStatus.value = CloudSyncStatus.Error
                _pendingEvent.value = CloudSyncEvent.Failure(
                    sessionManager.diagnose(error)
                )
            }
    }

    override fun clearPendingEvent() {
        _pendingEvent.value = null
    }

    private companion object {
        const val SYNC_TIMED_OUT =
            "Sync took too long and was stopped. Check your connection and try again."
    }
}
