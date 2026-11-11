package com.aus.notelikeus.ui.main

import com.aus.notelikeus.data.remote.AccountDeletionOutcomeUnknownException
import com.aus.notelikeus.data.remote.isReauthenticationRequiredRefusal
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.util.DateUtils
import com.aus.notelikeus.ui.components.clearAttachmentThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.account_delete_failed
import notelikeus.composeapp.generated.resources.account_delete_outcome_unknown
import notelikeus.composeapp.generated.resources.cloud_sign_out_delete_failed
import notelikeus.composeapp.generated.resources.cloud_sign_out_unsynced_check_failed

private const val AUTO_PULL_MIN_INTERVAL_MS = 30_000L

/**
 * How long a reconnect waits before pulling. Uploads queued offline (SyncWorker, CONNECTED
 * constraint) start on the same reconnect, and giving them a head start means the pull usually
 * finds them already in the cloud. Order is not what keeps data safe — a pull that lands first
 * keeps an unsynced local edit as a conflicted copy (see ConflictResolution) — it only saves a
 * round trip. It also folds a flapping connection into one pull.
 */
internal const val RECONNECT_PULL_DELAY_MS = 5_000L

/**
 * Cloud account and sync actions for the main screen. Everything sign-in, sign-out, sync, and
 * sync-event related lives here; MainViewModel exposes thin delegating methods so call sites in
 * the UI do not change.
 */
internal class CloudSyncController(
    private val state: MutableStateFlow<MainState>,
    private val scope: CoroutineScope,
    private val syncManager: SyncManager,
    /** Null where the platform has no [com.aus.notelikeus.domain.platform.NetworkMonitor]. */
    private val isOnline: StateFlow<Boolean>? = null,
) {
    private var lastAutoPullElapsedMs = 0L
    private var reconnectPull: Job? = null

    /** Mirrors the SyncManager flows into UI state. Call once from the ViewModel's init. */
    fun observe() {
        syncManager.cloudAccount.onEach { account ->
            state.update { it.copy(cloudAccount = account) }
        }.launchIn(scope)

        syncManager.syncStatus.onEach { status ->
            state.update { it.copy(cloudSyncStatus = status) }
        }.launchIn(scope)

        // Only non-null events are adopted: the flow resets to null on acknowledgement, and
        // mirroring that would wipe a failure this controller set itself (see
        // signInWithEmailCode, which reports straight into state rather than through the flow).
        syncManager.pendingEvent.onEach { event ->
            if (event != null) {
                state.update { current ->
                    current.copy(
                        pendingCloudSyncEvent = event,
                        // Nothing ever wrote cloudSyncedNoteCount. It was declared, threaded all
                        // the way down to ProfileSheet and rendered as "Last sync: %d notes", so
                        // that row read "Last sync: 0 notes" permanently, on every device, however
                        // much had actually synced. The counts only exist on these two events.
                        isDeletingAccount = current.isDeletingAccount && event !is CloudSyncEvent.AccountDeleted,
                        cloudSyncedNoteCount = when (event) {
                            is CloudSyncEvent.Uploaded -> event.noteCount
                            is CloudSyncEvent.Downloaded -> event.noteCount
                            else -> current.cloudSyncedNoteCount
                        }
                    )
                }
                // Decoded copies of the account's pictures. A device told this by its sync has not
                // been through deleteAccount below.
                if (event is CloudSyncEvent.AccountDeleted) clearAttachmentThumbnailCache()
            }
        }.launchIn(scope)

        isOnline?.let(::observeConnectivity)
    }

    /**
     * Pulls when the network comes back. Before this, a device that edited offline uploaded on
     * reconnect but showed nothing from other devices until the next foreground resume, which a
     * user who never left the app does not do.
     */
    private fun observeConnectivity(online: StateFlow<Boolean>) {
        var wasOnline = online.value
        online.onEach { now ->
            if (now && !wasOnline) {
                reconnectPull?.cancel()
                reconnectPull = scope.launch {
                    delay(RECONNECT_PULL_DELAY_MS)
                    // A sync already running started before the network was back, so it cannot
                    // have seen what changed meanwhile: wait it out rather than drop this pull.
                    state.first { it.cloudSyncStatus != CloudSyncStatus.Syncing }
                    autoSyncOnReconnect()
                }
            } else if (!now) {
                reconnectPull?.cancel()
            }
            wasOnline = now
        }.launchIn(scope)
    }

    fun enterOfflineMode() {
        state.update {
            it.copy(
                cloudAccount = CloudAccount(
                    email = null,
                    isSignedIn = false,
                    isAnonymous = false,
                    isOfflineMode = true
                )
            )
        }
    }

    /** Shows the sign-in gate again for a user who earlier chose local-only use. */
    fun requestSignIn() {
        state.update { it.copy(isSignInRequested = true) }
    }

    /** Backs out of a [requestSignIn] gate, back to the app as it was. */
    fun cancelSignInRequest() {
        state.update { it.copy(isSignInRequested = false, emailCodeSentTo = null) }
    }

    /**
     * Step one of email sign-in: asks for a one-time code. On success the gate moves to the code
     * step for [email]; resending calls this again with the same address.
     */
    fun requestEmailCode(email: String) {
        scope.launch {
            state.update { it.copy(isSigningIn = true) }
            val trimmed = email.trim()
            val result = syncManager.requestEmailCode(trimmed)
            state.update { currentState ->
                currentState.copy(
                    isSigningIn = false,
                    emailCodeSentTo = if (result.isSuccess) trimmed else currentState.emailCodeSentTo,
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: currentState.pendingCloudSyncEvent,
                )
            }
        }
    }

    /** Step two: signs in with the code emailed to [email], then pulls the account's notes. */
    fun signInWithEmailCode(email: String, code: String) {
        scope.launch {
            state.update { it.copy(isSigningIn = true) }
            val result = syncManager.signInWithEmailCode(email.trim(), code.trim())
            if (result.isSuccess) {
                // Immediately pull cloud notes after successful sign-in
                syncManager.downloadNotes()
            }
            state.update { currentState ->
                currentState.copy(
                    isSigningIn = false,
                    emailCodeSentTo = if (result.isSuccess) null else currentState.emailCodeSentTo,
                    isSignInRequested = if (result.isSuccess) false else currentState.isSignInRequested,
                    // Surfaced through pendingCloudSyncEvent, which SignInGate renders as
                    // externalError, so a wrong or expired code is said rather than swallowed.
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: currentState.pendingCloudSyncEvent
                )
            }
        }
    }

    /** "Use a different email": back to the address step. */
    fun resetEmailCode() {
        state.update { it.copy(emailCodeSentTo = null) }
    }

    private fun signInFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "Sign-in failed"

    fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) {
        scope.launch {
            state.update { it.copy(isSigningIn = true) }
            val result = syncManager.signInWithEmail(email, password, createAccount)
            state.update { currentState ->
                currentState.copy(
                    isSigningIn = false,
                    isSignInRequested = if (result.isSuccess) false else currentState.isSignInRequested,
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: currentState.pendingCloudSyncEvent
                )
            }
        }
    }

    /**
     * Sign-out failures are surfaced rather than dropped, because the only way
     * [SyncManager.signOut] fails is the one that matters: `deleteCloudData` was asked for and the
     * cloud delete did not happen,
     * so the session is deliberately still alive and the notes are still in the cloud. Silently
     * swallowing that left the user looking at a signed-in app with no explanation.
     */
    fun signOutFromCloud(deleteCloudData: Boolean = false, unsyncedConfirmed: Boolean = false) {
        scope.launch { performSignOut(deleteCloudData, unsyncedConfirmed) }
    }

    /** The user chose to stay signed in, so what has not synced can still sync now. */
    fun keepUnsyncedAndStaySignedIn() {
        state.update { it.copy(unsyncedBeforeSignOut = null) }
    }

    private suspend fun performSignOut(deleteCloudData: Boolean, unsyncedConfirmed: Boolean) {
        // AUDIT-001 / NL-03: an ordinary sign-out clears this device's notes. Anything the cloud does not
        // have yet is kept, sealed for this account, but it then waits for the account to sign in here
        // again, so sync once, and if work is still only here, say so first.
        // Deleting the cloud data is already a choice to delete everything, so it is not asked again.
        if (!deleteCloudData && !unsyncedConfirmed) {
            val unsynced = runCatching { syncManager.unsyncedWorkBeforeSignOut() }.getOrElse { error ->
                // A check that failed proves nothing, so it cannot clear the way to deleting.
                if (error is kotlinx.coroutines.CancellationException) throw error
                state.update {
                    it.copy(
                        pendingCloudSyncEvent = CloudSyncEvent.Failure(
                            Res.string.cloud_sign_out_unsynced_check_failed,
                        ),
                    )
                }
                return
            }
            if (!unsynced.isEmpty) {
                state.update { it.copy(unsyncedBeforeSignOut = unsynced) }
                return
            }
        }
        state.update { it.copy(unsyncedBeforeSignOut = null) }
        val result = syncManager.signOut(deleteCloudData)
        // Card thumbnails are decoded copies of the account's pictures; they do not outlive its session.
        if (result.isSuccess) clearAttachmentThumbnailCache()
        // The server wants a recent sign-in first (AUD-005). Nothing was wiped and the user is still
        // signed in, so this asks for a code rather than reporting a failure.
        if (deleteCloudData && result.exceptionOrNull()?.isReauthenticationRequiredRefusal() == true) {
            requireIdentityCheck(IdentityCheck.WipeCloudData)
            return
        }
        state.update { currentState ->
            currentState.copy(
                pendingCloudSyncEvent = result.fold(
                    // Nothing emitted CloudSyncEvent.SignedOut, so the branch rendering it and
                    // both its strings were unreachable. Confirming a destructive action is
                    // worth more than deleting the code that was already written for it —
                    // "sign out and delete my cloud notes" should say that it happened.
                    onSuccess = { CloudSyncEvent.SignedOut(cloudDataDeleted = deleteCloudData) },
                    onFailure = { signOutFailure(it) }
                )
            )
        }
    }

    private fun signOutFailure(error: Throwable): CloudSyncEvent.Failure =
        error.message?.takeIf { it.isNotBlank() }?.let { CloudSyncEvent.Failure(it) }
            ?: CloudSyncEvent.Failure(Res.string.cloud_sign_out_delete_failed)

    /**
     * Deletes the account, after the user typed the confirmation. A failure is reported and changes
     * nothing: the account and this device's notes are both still there, and the user can retry.
     */
    fun deleteAccount() {
        if (state.value.isDeletingAccount) return
        scope.launch { performDeleteAccount() }
    }

    private suspend fun performDeleteAccount() {
        state.update { it.copy(isDeletingAccount = true) }
        val result = syncManager.deleteAccount()
        if (result.isSuccess) clearAttachmentThumbnailCache()
        // The confirmation is already given, so a recent sign-in is all that is still asked for.
        if (result.exceptionOrNull()?.isReauthenticationRequiredRefusal() == true) {
            state.update { it.copy(isDeletingAccount = false) }
            requireIdentityCheck(IdentityCheck.DeleteAccount)
            return
        }
        state.update { currentState ->
            currentState.copy(
                isDeletingAccount = false,
                pendingCloudSyncEvent = result.fold(
                    onSuccess = { CloudSyncEvent.AccountDeleted() },
                    onFailure = { CloudSyncEvent.Failure(accountDeleteFailureMessage(it)) },
                ),
            )
        }
    }

    /**
     * "Nothing was deleted" only when that is known (AUD-010). After a failure that may have come after
     * the server accepted the request, and that asking the server could not settle, it says so instead.
     */
    private fun accountDeleteFailureMessage(error: Throwable): StringResource =
        if (generateSequence(error) { it.cause }.take(8).any { it is AccountDeletionOutcomeUnknownException }) {
            Res.string.account_delete_outcome_unknown
        } else {
            Res.string.account_delete_failed
        }

    private fun requireIdentityCheck(check: IdentityCheck) {
        state.update {
            it.copy(pendingIdentityCheck = check, identityCodeSent = false, isConfirmingIdentity = false)
        }
    }

    /**
     * "Confirm it's you", step one: emails a sign-in code to the signed-in account's own address.
     *
     * Deliberately not [requestEmailCode], which drives the sign-in screen: the user is signed in, and
     * this only starts a fresh session for the same account.
     */
    fun requestIdentityCode() {
        val email = state.value.cloudAccount.email
        if (state.value.pendingIdentityCheck == null || email.isNullOrBlank()) return
        scope.launch {
            state.update { it.copy(isConfirmingIdentity = true) }
            val result = syncManager.requestEmailCode(email)
            state.update { currentState ->
                currentState.copy(
                    isConfirmingIdentity = false,
                    identityCodeSent = currentState.identityCodeSent || result.isSuccess,
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: currentState.pendingCloudSyncEvent,
                )
            }
        }
    }

    /**
     * Step two: signs in again with [code], then runs the waiting action with that fresh session. A
     * wrong code keeps the dialog open; the action runs only after the code was accepted.
     */
    fun confirmIdentity(code: String) {
        val check = state.value.pendingIdentityCheck ?: return
        val email = state.value.cloudAccount.email ?: return
        scope.launch {
            state.update { it.copy(isConfirmingIdentity = true) }
            val signedIn = syncManager.signInWithEmailCode(email, code.trim())
            if (signedIn.isFailure) {
                state.update {
                    it.copy(
                        isConfirmingIdentity = false,
                        pendingCloudSyncEvent = CloudSyncEvent.Failure(
                            signInFailureMessage(signedIn.exceptionOrNull()!!),
                        ),
                    )
                }
                return@launch
            }
            clearIdentityCheck()
            when (check) {
                IdentityCheck.DeleteAccount -> performDeleteAccount()
                IdentityCheck.WipeCloudData -> performSignOut(deleteCloudData = true, unsyncedConfirmed = false)
            }
        }
    }

    /** Leaves the action undone. Nothing was deleted, and nothing is owed. */
    fun cancelIdentityCheck() = clearIdentityCheck()

    private fun clearIdentityCheck() {
        state.update {
            it.copy(pendingIdentityCheck = null, identityCodeSent = false, isConfirmingIdentity = false)
        }
    }

    fun syncNotesToCloud() {
        scope.launch {
            syncManager.syncNotes()
        }
    }

    /**
     * "Upload this device's notes again", after a wipe this device has not acknowledged.
     *
     * Surfaces a failure rather than swallowing it: the notes are still on the device either way,
     * but a user who pressed this button needs to know whether anything was sent.
     */
    fun allowUploadAfterCloudWipe() {
        scope.launch {
            val result = syncManager.allowUploadAfterCloudWipe()
            result.exceptionOrNull()?.let { failure ->
                state.update { it.copy(pendingCloudSyncEvent = CloudSyncEvent.Failure(signInFailureMessage(failure))) }
            }
        }
    }

    fun downloadNotesFromCloud() {
        scope.launch {
            syncManager.downloadNotes()
        }
    }

    /**
     * Pull-to-refresh on the notes list. An explicit request, so it ignores the auto-sync setting
     * and the foreground throttle, but not a signed-out account or a sync already running.
     */
    fun refreshFromCloud() {
        if (state.value.cloudSyncStatus == CloudSyncStatus.Syncing) return
        if (!state.value.cloudAccount.isSignedIn) return
        lastAutoPullElapsedMs = DateUtils.currentTimeMillis()
        downloadNotesFromCloud()
    }

    /**
     * Best-effort pull when the app returns to the foreground.
     */
    fun autoSyncOnForeground() {
        if (!canAutoPull()) return
        val now = DateUtils.currentTimeMillis()
        if (now - lastAutoPullElapsedMs < AUTO_PULL_MIN_INTERVAL_MS) return
        lastAutoPullElapsedMs = now
        downloadNotesFromCloud()
    }

    /**
     * The reconnect pull. It skips the foreground throttle on purpose: a resume a few seconds
     * before the network came back pulled nothing, and throttling against it would push the first
     * useful pull out by up to 30 seconds. It still stamps the throttle, so a resume right after
     * does not pull a second time.
     */
    private fun autoSyncOnReconnect() {
        if (!canAutoPull()) return
        lastAutoPullElapsedMs = DateUtils.currentTimeMillis()
        downloadNotesFromCloud()
    }

    private fun canAutoPull(): Boolean {
        val current = state.value
        return current.cloudSyncStatus != CloudSyncStatus.Syncing &&
            current.cloudAccount.isSignedIn &&
            current.isCloudAutoSyncEnabled
    }

    fun clearPendingCloudSyncEvent() {
        syncManager.clearPendingEvent()
        state.update { it.copy(pendingCloudSyncEvent = null) }
    }
}
