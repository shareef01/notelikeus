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

class AndroidSyncManager(
    private val sessionManager: CloudSessionManager,
    private val syncEngine: NoteSyncEngine,
    private val isolator: LocalAccountIsolator,
    /** F-8: owns the durable record of an accepted destructive wipe request. */
    private val cloudWipeCoordinator: CloudWipeCoordinator,
    /** Timestamps the accepted request; injected so tests stay deterministic. */
    private val now: () -> Long = { System.currentTimeMillis() },
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

    private suspend fun onSignedIn() {
        refreshAccount()
        val uid = sessionManager.getCurrentAccount().userId ?: return
        isolator.isolateIfAccountChanged(uid)
        // F-8: an accepted destructive request that never reached authoritative completion is resumed
        // here — for *this* uid only, and always through a freshly captured identity, never one borrowed
        // from whoever is signed in. A failure leaves it pending and silent; it is not lost.
        cloudWipeCoordinator.resumeIfPending(uid) { syncEngine.deleteAllCloudData().getOrThrow() }
    }

    /** Android exchanges its token through [signInWithGoogle]; nothing signs in outside it. */
    override suspend fun completeExternalSignIn(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Android signs in via signInWithGoogle"))

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return sessionManager.signInWithGoogle(idToken).onSuccess {
            onSignedIn()
        }
    }

    override suspend fun signInWithEmail(email: String, password: String, create: Boolean): Result<Unit> {
        return sessionManager.signInWithEmailPassword(email, password, create).onSuccess {
            onSignedIn()
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
     * there is permanent once the session is gone: the notes stay in the cloud and nothing can
     * retry. This used to discard the Result and sign out anyway, which reported success for a
     * delete that never happened — and offline or on an expired token is exactly when a user
     * reaches for "sign out and delete". Fail the sign-out instead and leave the session intact so
     * the request can be made again.
     */
    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
        if (deleteCloudData) {
            // Persist the accepted request before any destructive work (F-8): a wipe whose record could
            // not be written does not start at all, and a wipe that fails stays owed rather than
            // forgotten, which is what makes a later session able to finish it.
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
            refreshAccount()
        }
    }

    override suspend fun syncNotes() {
        isolateIncomingSession()
        runSync({ CloudSyncEvent.Uploaded(it) }) { syncEngine.uploadAllNotes() }
    }

    override suspend fun downloadNotes() {
        isolateIncomingSession()
        runSync({ CloudSyncEvent.Downloaded(it) }) { syncEngine.downloadAllNotes() }
    }

    private suspend fun isolateIncomingSession() {
        sessionManager.getCurrentAccount().userId?.let { isolator.isolateIfAccountChanged(it) }
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
