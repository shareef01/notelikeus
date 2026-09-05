package com.aus.notelikeus.ui.main

import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val AUTO_PULL_MIN_INTERVAL_MS = 30_000L

/**
 * Cloud account and sync actions for the main screen. Everything sign-in, sign-out, sync, and
 * sync-event related lives here; MainViewModel exposes thin delegating methods so call sites in
 * the UI do not change.
 */
internal class CloudSyncController(
    private val state: MutableStateFlow<MainState>,
    private val scope: CoroutineScope,
    private val syncManager: SyncManager
) {
    private var lastAutoPullElapsedMs = 0L

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
     * so the session is deliberately still alive and the notes are still in Firestore. Silently
     * swallowing that left the user looking at a signed-in app with no explanation.
     */
    fun signOutFromCloud(deleteCloudData: Boolean = false) {
        scope.launch {
            val result = syncManager.signOut(deleteCloudData)
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
     * Best-effort pull when the app returns to the foreground.
     */
    fun autoSyncOnForeground() {
        if (state.value.cloudSyncStatus == CloudSyncStatus.Syncing) return
        if (!state.value.cloudAccount.isGoogleAccount) return
        if (!state.value.isCloudAutoSyncEnabled) return
        val now = DateUtils.currentTimeMillis()
        if (now - lastAutoPullElapsedMs < AUTO_PULL_MIN_INTERVAL_MS) return
        lastAutoPullElapsedMs = now
        scope.launch {
            syncManager.downloadNotes()
        }
    }

    fun clearPendingCloudSyncEvent() {
        syncManager.clearPendingEvent()
        state.update { it.copy(pendingCloudSyncEvent = null) }
    }
}
