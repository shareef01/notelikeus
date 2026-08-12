package com.aus.notelikeus.ui.auth

/**
 * Platform Google sign-in, producing an ID token that the sync layer exchanges for a session.
 *
 * The previous shape leaked the Android implementation into common code: `getSignInIntent(): Any?`
 * plus `parseIdToken(data: Any?)` modelled the Intent/ActivityResult round-trip, forcing callers to
 * cast (`getSignInIntent() as Intent`, which throws if the platform returns null) and giving the
 * compiler nothing to check. A single suspending call carries no platform types.
 */
interface GoogleSignInHelper {
    /** False when this platform or device cannot perform Google sign-in at all. */
    fun isAvailable(): Boolean

    /**
     * Shows the platform's sign-in UI and returns a Google ID token.
     *
     * Must be called from a coroutine tied to the visible UI, since it presents a dialog.
     * Cancellation by the user is reported as a failed [Result], not an exception.
     */
    suspend fun requestIdToken(): Result<String>
}
