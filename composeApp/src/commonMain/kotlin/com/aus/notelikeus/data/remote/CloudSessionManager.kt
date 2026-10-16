package com.aus.notelikeus.data.remote

data class CloudSessionAccount(
    val userId: String?,
    val email: String?,
    val isSignedIn: Boolean,
    val isAnonymous: Boolean,
)

interface CloudSessionManager {
    fun getCurrentAccount(): CloudSessionAccount
    /** Emails a one-time sign-in code; the account is created on first use. */
    suspend fun requestEmailCode(email: String): Result<Unit>

    /** Signs in with the code [requestEmailCode] sent, and saves the session. */
    suspend fun signInWithEmailCode(email: String, code: String): Result<Unit>
    suspend fun signInWithEmailPassword(
        email: String,
        password: String,
        createAccount: Boolean,
    ): Result<Unit>
    suspend fun signOut(): Result<Unit>
    suspend fun ensureSignedIn(): Result<String>
    fun diagnose(error: Throwable): String
}
