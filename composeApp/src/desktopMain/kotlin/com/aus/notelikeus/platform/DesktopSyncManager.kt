package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.AccountDeletionTransport
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.isAccountDeletedRefusal
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
import com.aus.notelikeus.data.sync.CloudWipeOutcome
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.runTimedSync
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.domain.repository.UnsyncedWork
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.repository.LocalCommitToken
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
    private val accountDeletion: AccountDeletionTransport,
    /** Timestamps the accepted request; injected so tests stay deterministic. */
    private val now: () -> Long = { System.currentTimeMillis() },
) : SyncManager {

    /**
     * Where sign-in, sign-out and sync do their work. The view models call this class from the UI
     * thread, and nothing below it switches threads on its own: the engine resumes on its caller
     * after each database or network call, and DesktopNoteSyncStateStore blocks on every write. Left
     * on the caller, a sync of a few hundred notes is a few hundred disk writes on the UI thread.
     */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val accountLifecycle = Mutex()

    private fun captureAccount() = LocalCommitGate.captureCoherent { sessionManager.getCurrentAccount().userId }

    private val _syncStatus = MutableStateFlow(CloudSyncStatus.Offline)
    override val syncStatus: StateFlow<CloudSyncStatus> = _syncStatus.asStateFlow()

    private val _cloudAccount = MutableStateFlow(
        sessionManager.getCurrentAccount().let { account ->
            if (account.isSignedIn && account.userId != null) {
                CloudAccount(
                    email = account.email,
                    isSignedIn = true,
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

    private suspend fun onSignedIn() {
        refreshAccount()
        val uid = sessionManager.getCurrentAccount().userId ?: return
        isolator.isolateIfAccountChanged(uid)
        // F-8: resume an accepted destructive request for *this* uid only, through a freshly captured
        // identity. A failure leaves it pending rather than lost.
        cloudWipeCoordinator.resumeIfPending(uid) { syncEngine.deleteAllCloudData().getOrThrow() }
    }

    override suspend fun requestEmailCode(email: String): Result<Unit> =
        sessionManager.requestEmailCode(email)

    override suspend fun signInWithEmailCode(email: String, code: String): Result<Unit> =
        withContext(workDispatcher) { signInWithEmailCodeOnWorker(email, code) }

    private suspend fun signInWithEmailCodeOnWorker(email: String, code: String): Result<Unit> {
        return accountLifecycle.withLock {
            sessionManager.signInWithEmailCode(email, code).onSuccess {
                onSignedIn()
            }
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
        create: Boolean
    ): Result<Unit> = withContext(workDispatcher) { signInWithEmailOnWorker(email, password, create) }

    private suspend fun signInWithEmailOnWorker(
        email: String,
        password: String,
        create: Boolean
    ): Result<Unit> {
        return accountLifecycle.withLock {
            // Every sign-in runs onSignedIn: without it the session is saved but the account state,
            // account isolation and any owed cloud wipe never catch up, and the UI stays on the gate.
            sessionManager.signInWithEmailPassword(email, password, create).onSuccess {
                onSignedIn()
            }
        }
    }

    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> =
        withContext(workDispatcher) { signOutOnWorker(deleteCloudData) }

    private suspend fun signOutOnWorker(deleteCloudData: Boolean): Result<Unit> {
        return accountLifecycle.withLock {
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
                    // Nothing was wiped and the request is not owed; the caller asks for a new code (AUD-005).
                    is CloudWipeOutcome.ReauthenticationRequired -> return Result.failure(outcome.failure)
                    CloudWipeOutcome.Completed, is CloudWipeOutcome.CompletedButStillPending -> Unit
                }
            }
            sessionManager.signOut().onSuccess {
                isolator.isolate()
                _syncStatus.value = CloudSyncStatus.Offline
                _cloudAccount.value = CloudAccount(isOfflineMode = true)
            }
        }
    }

    override suspend fun unsyncedWorkBeforeSignOut(): UnsyncedWork =
        withContext(workDispatcher) { syncEngine.unsyncedWorkAfterSyncAttempt() }

    override suspend fun deleteAccount(): Result<Unit> = withContext(workDispatcher) {
        val origin = captureAccount()
        runCatching<Unit> {
            val owner = origin.initiatingUid ?: error("Sign in again to delete your account.")
            accountDeletion.deleteAccount(owner)
            accountLifecycle.withLock { endDeletedAccountSession(origin) }
        }
    }

    /**
     * The account is gone, so its session and this device's copy of its notes go too. Called only on
     * a definite answer from the server. A failure here is not reported as a failed deletion: an
     * interrupted isolation is durable and completes at the next startup.
     */
    private suspend fun endDeletedAccountSession(origin: LocalCommitToken): Boolean {
        if (origin.initiatingUid == null || sessionManager.getCurrentAccount().userId != origin.initiatingUid ||
            LocalCommitGate.currentGeneration() != origin.generation) return false
        runCatching { sessionManager.signOut() }
        isolator.isolate()
        _syncStatus.value = CloudSyncStatus.Offline
        _cloudAccount.value = CloudAccount(isOfflineMode = true)
        return true
    }

    override suspend fun syncNotes() = withContext(workDispatcher) { syncNotesOnWorker() }

    private suspend fun syncNotesOnWorker() {
        sessionManager.getCurrentAccount().userId?.let { isolator.isolateIfAccountChanged(it) }
        runSync { syncEngine.uploadAllNotes() }
    }

    override suspend fun downloadNotes() = withContext(workDispatcher) { downloadNotesOnWorker() }

    private suspend fun downloadNotesOnWorker() {
        sessionManager.getCurrentAccount().userId?.let { isolator.isolateIfAccountChanged(it) }
        // Desktop downloads stay quiet, except to say a deleted note came back (AU-07) or that
        // words were kept from a note deleted elsewhere.
        runSync(
            successEvent = { count ->
                val back = syncEngine.takeDeletedNotesCameBack()
                val kept = syncEngine.takeEditsKeptFromDeletedNotes()
                if (back > 0 || kept > 0) CloudSyncEvent.Downloaded(count, back, kept) else null
            },
        ) { syncEngine.downloadAllNotes() }
    }

    /**
     * Lifts the cloud-wipe block and uploads, in that order: the block is what has been stopping
     * every upload, so it has to go first, and the upload is the whole point of asking.
     */
    override suspend fun allowUploadAfterCloudWipe(): Result<Unit> = withContext(workDispatcher) {
        val lifted = syncEngine.allowUploadAfterCloudWipe()
        if (lifted.isSuccess) syncNotesOnWorker()
        lifted
    }

    private suspend fun runSync(
        successEvent: (Int) -> CloudSyncEvent? = { null },
        block: suspend () -> Result<Int>,
    ) {
        val origin = captureAccount()
        var accountDeleted = false
        runTimedSync(
            status = _syncStatus,
            pendingEvent = _pendingEvent,
            describeError = sessionManager::diagnose,
            successEvent = successEvent,
            onFailure = {
                accountDeleted = it.isAccountDeletedRefusal()
                if (sessionManager.getCurrentAccount().userId == null) {
                    _cloudAccount.value = CloudAccount(isOfflineMode = true)
                }
            },
            block = block,
        )
        if (accountDeleted && accountLifecycle.withLock { endDeletedAccountSession(origin) }) {
            _pendingEvent.value = CloudSyncEvent.AccountDeleted(elsewhere = true)
        }
    }

    override fun clearPendingEvent() {
        _pendingEvent.value = null
    }

    private fun refreshAccount() {
        val account = sessionManager.getCurrentAccount()
        _cloudAccount.update {
            CloudAccount(
                email = account.email,
                isSignedIn = account.isSignedIn,
                isAnonymous = account.isAnonymous,
            )
        }
    }
}
