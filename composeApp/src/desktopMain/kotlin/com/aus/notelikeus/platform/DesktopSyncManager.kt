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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
     * which is the only place that sees the refresh token. The idToken is validated here by
     * extracting the subject claim and checking it against the stored uid — rejecting a stale or
     * mismatched token rather than silently ignoring it.
     */
    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        if (!tokenStore.hasSession() || tokenStore.uid() == null) {
            _syncStatus.value = CloudSyncStatus.Offline
            return Result.failure(IllegalStateException("Sign-in did not produce a usable session"))
        }
        // Validate the token matches the stored session — a stale or mismatched token is a bug.
        val tokenUid = extractJwtSubject(idToken)
        if (tokenUid != null && tokenUid != tokenStore.uid()) {
            return Result.failure(IllegalStateException("Token uid ($tokenUid) does not match stored session"))
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

    /**
     * See AndroidSyncManager.signOut: clearing the token store below destroys the credential
     * [NoteSyncEngine.deleteAllCloudData] needs, so a swallowed failure leaves the notes in
     * Firestore with no way to retry and still reports success.
     */
    override suspend fun signOut(deleteCloudData: Boolean): Result<Unit> {
        if (deleteCloudData) {
            syncEngine.deleteAllCloudData().onFailure { return Result.failure(it) }
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

    private companion object {
        const val SYNC_TIMED_OUT =
            "Sync took too long and was stopped. Check your connection and try again."
    }

    private suspend fun runSync(block: suspend () -> Result<Int>) {
        _syncStatus.value = CloudSyncStatus.Syncing
        val result = withTimeoutOrNull(syncTimeoutMs) { block() }
        if (result == null) {
            _syncStatus.value = CloudSyncStatus.Error
            _pendingEvent.value = CloudSyncEvent.Failure(SYNC_TIMED_OUT)
            return
        }
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

/**
 * Extracts the "sub" claim from a Firebase ID token (JWT) without verifying the signature.
 * This is only used for a consistency check against the already-trusted stored session;
 * real token verification happens inside [com.aus.notelikeus.platform.DesktopGoogleSignInHelper].
 */
private fun extractJwtSubject(idToken: String): String? {
    return try {
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
        val json = Json.parseToJsonElement(payload).jsonObject
        // Match DesktopTokenStore.decodeJwt claim precedence: sub first, then user_id.
        json["sub"]?.jsonPrimitive?.content ?: json["user_id"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
