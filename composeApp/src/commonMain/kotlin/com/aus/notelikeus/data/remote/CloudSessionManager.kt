package com.aus.notelikeus.data.remote

data class CloudSessionAccount(
    val userId: String?,
    val email: String?,
    val isGoogleAccount: Boolean,
    val isAnonymous: Boolean,
)

interface CloudSessionManager {
    fun getCurrentAccount(): CloudSessionAccount
    suspend fun signInWithGoogle(idToken: String): Result<Unit>
    suspend fun signInWithEmailPassword(
        email: String,
        password: String,
        createAccount: Boolean,
    ): Result<Unit>
    suspend fun signOut(): Result<Unit>
    suspend fun ensureSignedIn(): Result<String>
    fun diagnose(error: Throwable): String
}
