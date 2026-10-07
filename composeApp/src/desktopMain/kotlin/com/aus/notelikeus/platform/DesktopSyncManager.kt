package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
import com.aus.notelikeus.data.sync.CloudWipeOutcome
import com.aus.notelikeus.data.sync.LocalAccountIsolator
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

class DesktopSyncManager(
    private val syncEngine: NoteSyncEngine,
    private val sessionManager: CloudSessionManager,
    private val isolator: LocalAccountIsolator,
    /** F-8: owns the durable record of an accepted destructive wipe request. */
    private val cloudWipeCoordinator: CloudWipeCoordinator,
    /** Timestamps the accepted request; injected so tests stay deterministic. */
    private val now: () -> Long = { System.currentTimeMillis() },
) : SyncManager {

    private val _syncStatus = MutableStateFlow(CloudSyncStatus.Offline)
    override val syncStatus: StateFlow<CloudSyncStatus> = _syncStatus.asStateFlow()

    private val _cloudAccount = MutableStateFlow(
        sessionManager.getCurrentAccount().let { account ->
            if (account.isGoogleAccount && account.userId != null) {
                CloudAccount(
                    email = account.email,
                    isGoogleAccount = true,
                    isAnonymous = false,
                )
            } else {
                CloudAccount(isOfflineMode = true)
            }
        },
    )
    override val cloudAccount: StateFlow<CloudAccount> = _cloudAccount.asStateFlow()

    private val _pendingEvent = MutableStateFlow<CloudSyncEvent?>(null)
    override val pendingEvent: StateFlow<CloudSyncEvent?> = _pendingEvent.asStateFlow()

    override suspend fun completeExternalSignIn(): Result<Unit> = runCatching {
        // The helper already exchanged the Google token and saved the session; everything that
        // normally follows a sign-in still has to run.
        onSignedIn()
    }

    private suspend fun onSignedIn() {
        refreshAccount()
        val uid = sessionManager.getCurrentAccount().userId ?: return
        isolator.isolateIfAccountChanged(uid)
        // F-8: resume an accepted destructive request for *this* uid only, through a freshly captured
        // identity. A failure leaves it pending rather than lost.
        cloudWipeCoordinator.resumeIfPending(uid) { syncEngine.deleteAllCloudData().getOrThrow() }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return sessionManager.signInWithGoogle(idToken).onSuccess {
            onSignedIn()
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
        create: Boolean
    ): Result<Unit> = sessionManager.signInWithEmailPassword(email, password, create)

    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
        if (deleteCloudData) {
            // Persist the accepted request before any destructive work (F-8) — same ordering as Android,
            // on a durable store that local account isolation does not touch.
            val owner = sessionManager.getCurrentAccount().userId
                ?: return Result.failure(IllegalStateException("not signed in"))
            when (
                val outcome = cloudWipeCoordinator.requestWipe(owner, now()) {
                    syncEngine.deleteAllCloudData().getOrThrow()
                }
            ) {
                is CloudWipeOutcome.NotStarted -> return Result.failure(outcome.failure)
                is CloudWipeOutcome.Failed -> return Result.failure(outcome.failure)
                CloudWipeOutcome.Completed, is CloudWipeOutcome.CompletedButStillPending -> Unit
            }
        }
        return sessionManager.signOut().onSuccess {
            isolator.isolate()
            _syncStatus.value = CloudSyncStatus.Offline
            _cloudAccount.value = CloudAccount(isOfflineMode = true)
        }
    }

    override suspend fun syncNotes() {
        sessionManager.getCurrentAccount().userId?.let { isolator.isolateIfAccountChanged(it) }
        runSync { syncEngine.uploadAllNotes() }
    }

    override suspend fun downloadNotes() {
        sessionManager.getCurrentAccount().userId?.let { isolator.isolateIfAccountChanged(it) }
        runSync { syncEngine.downloadAllNotes() }
    }

    private suspend fun runSync(block: suspend () -> Result<Int>) = runTimedSync(
        status = _syncStatus,
        pendingEvent = _pendingEvent,
        describeError = sessionManager::diagnose,
        onFailure = {
            if (sessionManager.getCurrentAccount().userId == null) {
                _cloudAccount.value = CloudAccount(isOfflineMode = true)
            }
        },
        block = block,
    )

    override fun clearPendingEvent() {
        _pendingEvent.value = null
    }

    private fun refreshAccount() {
        val account = sessionManager.getCurrentAccount()
        _cloudAccount.update {
            CloudAccount(
                email = account.email,
                isGoogleAccount = account.isGoogleAccount,
                isAnonymous = account.isAnonymous,
            )
        }
    }
}
