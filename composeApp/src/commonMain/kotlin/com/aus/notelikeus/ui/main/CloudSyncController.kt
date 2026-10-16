package com.aus.notelikeus.ui.main

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
        // signInWithGoogleIdToken, which reports before any SyncManager call happens).
        syncManager.pendingEvent.onEach { event ->
            if (event != null) {
                state.update { current ->
                    current.copy(
                        pendingCloudSyncEvent = event,
                        // Nothing ever wrote cloudSyncedNoteCount. It was declared, threaded all
                        // the way down to ProfileSheet and rendered as "Last sync: %d notes", so
                        // that row read "Last sync: 0 notes" permanently, on every device, however
                        // much had actually synced. The counts only exist on these two events.
                        cloudSyncedNoteCount = when (event) {
                            is CloudSyncEvent.Uploaded -> event.noteCount
                            is CloudSyncEvent.Downloaded -> event.noteCount
                            else -> current.cloudSyncedNoteCount
                        }
                    )
                }
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
                    isGoogleAccount = false,
                    isAnonymous = false,
                    isOfflineMode = true
                )
            )
        }
    }

    /**
     * Finishes a sign-in that the platform helper already completed itself.
     *
     * The desktop Supabase path exchanges the Google ID token, saves the session and returns the
     * resulting *Supabase* access token. Feeding that back through [signInWithGoogleIdToken] posted
     * a Supabase JWT to `grant_type=id_token`, which Supabase rejected with `Bad ID token` — so a
     * sign-in that had in fact succeeded surfaced as a failure and left the user on the gate.
     *
     * There is nothing left to exchange here: `cloudAccount` mirrors the SyncManager flow, so the
     * saved session surfaces on its own once notes are pulled.
     */
    fun completeExternalSignIn() {
        scope.launch {
            state.update { it.copy(isSigningIn = true) }
            val result = syncManager.completeExternalSignIn()
            if (result.isSuccess) {
                syncManager.downloadNotes()
            }
            state.update { currentState ->
                currentState.copy(
                    isSigningIn = false,
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: currentState.pendingCloudSyncEvent,
                )
            }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        scope.launch {
            state.update { it.copy(isSigningIn = true) }
            val result = syncManager.signInWithGoogle(idToken)
            if (result.isSuccess) {
                // Immediately pull cloud notes after successful sign-in
                syncManager.downloadNotes()
            }
            state.update { currentState ->
                currentState.copy(
                    isSigningIn = false,
                    // Surfaced through pendingCloudSyncEvent, which SignInGate already renders as
                    // externalError. This used to drop the Result on the floor, so a failed
                    // sign-in just silently returned the user to the gate.
                    pendingCloudSyncEvent = result.exceptionOrNull()
                        ?.let { CloudSyncEvent.Failure(signInFailureMessage(it)) }
                        ?: currentState.pendingCloudSyncEvent
                )
            }
        }
    }

    /**
     * Reports a failure from the platform sign-in UI itself (before any token exists) — user
     * cancellation, no Google account on the device, no Play Services, and so on.
     */
    fun reportGoogleSignInFailure(error: Throwable) {
        state.update {
            it.copy(
                isSigningIn = false,
                pendingCloudSyncEvent = CloudSyncEvent.Failure(signInFailureMessage(error))
            )
        }
    }

    private fun signInFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "Google sign-in failed"

    fun signInWithEmailPassword(email: String, password: String, createAccount: Boolean) {
        scope.launch {
            state.update { it.copy(isSigningIn = true) }
            val result = syncManager.signInWithEmail(email, password, createAccount)
            state.update { currentState ->
                currentState.copy(
                    isSigningIn = false,
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
    fun signOutFromCloud(deleteCloudData: Boolean = false) {
        scope.launch {
            val result = syncManager.signOut(deleteCloudData)
            // Card thumbnails are decoded copies of the account's pictures; they do not outlive its session.
            if (result.isSuccess) clearAttachmentThumbnailCache()
            state.update { currentState ->
                currentState.copy(
                    pendingCloudSyncEvent = result.fold(
                        // Nothing emitted CloudSyncEvent.SignedOut, so the branch rendering it and
                        // both its strings were unreachable. Confirming a destructive action is
                        // worth more than deleting the code that was already written for it —
                        // "sign out and delete my cloud notes" should say that it happened.
                        onSuccess = { CloudSyncEvent.SignedOut(cloudDataDeleted = deleteCloudData) },
                        onFailure = { CloudSyncEvent.Failure(signOutFailureMessage(it)) }
                    )
                )
            }
        }
    }

    private fun signOutFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }
            ?: "Couldn't delete your cloud notes, so you're still signed in. Try again."

    fun syncNotesToCloud() {
        scope.launch {
            syncManager.syncNotes()
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
        if (!state.value.cloudAccount.isGoogleAccount) return
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
            current.cloudAccount.isGoogleAccount &&
            current.isCloudAutoSyncEnabled
    }

    fun clearPendingCloudSyncEvent() {
        syncManager.clearPendingEvent()
        state.update { it.copy(pendingCloudSyncEvent = null) }
    }
}
