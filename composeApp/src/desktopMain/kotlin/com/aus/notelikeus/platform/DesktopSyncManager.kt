package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.FirestoreTransportException
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.SuspectEmptyCloudException
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncEvent
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

    private val _pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)
    override val pendingEvent: StateFlow<CloudSyncEvent?> = _pendingEvent.asStateFlow()

    /**
     * The session itself is persisted by [com.aus.notelikeus.platform.DesktopGoogleSignInHelper],
     * which is the only place that sees the refresh token. This just confirms it landed and
     * reflects it in the UI state.
     */
    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        if (!tokenStore.hasSession() || tokenStore.uid() == null) {
            _syncStatus.value = CloudSyncStatus.Offline
            return Result.failure(IllegalStateException("Sign-in did not produce a usable session"))
        }
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
        runSync { syncEngine.uploadAllNotes() }
    }

    override suspend fun downloadNotes() {
        runSync { syncEngine.downloadAllNotes() }
    }

    /**
     * Reports what actually happened.
     *
     * Both entry points used to discard the [Result] and set [CloudSyncStatus.Synced]
     * unconditionally, which is why a completely non-functional cloud path still displayed as
     * healthy.
     */
    private suspend fun runSync(block: suspend () -> Result<Int>) {
        _syncStatus.value = CloudSyncStatus.Syncing
        val result = block()
        result
            .onSuccess { _syncStatus.value = CloudSyncStatus.Synced }
            .onFailure { error ->
                _syncStatus.value = CloudSyncStatus.Error
                _pendingEvent.value = CloudSyncEvent.Failure(describe(error))
                // A rejected refresh token clears the stored session, so stop showing an account
                // the app can no longer act as.
                if (!tokenStore.hasSession()) {
                    _cloudAccount.value = CloudAccount(isOfflineMode = true)
                }
            }
    }

    private fun describe(error: Throwable): String = when {
        error is SuspectEmptyCloudException -> error.message ?: "Sync stopped to protect your notes."
        error is FirestoreTransportException && error.isAuthFailure ->
            "Your session expired. Sign in again to sync."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Sync failed. Try again."
    }

    override fun clearPendingEvent() {
        _pendingEvent.value = null
    }

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
