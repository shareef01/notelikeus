package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig

class SupabaseSessionManager(
    private val authApi: SupabaseAuthApi,
    private val sessionStore: SupabaseSessionStore,
) : CloudSessionManager {

    override fun getCurrentAccount(): CloudSessionAccount = sessionStore.account()

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> = runCatching {
        val session = authApi.signInWithGoogleIdToken(idToken)
        sessionStore.save(session)
    }

    override suspend fun signInWithEmailPassword(
        email: String,
        password: String,
        createAccount: Boolean,
    ): Result<Unit> {
        if (!AppConfig.isDebug) {
            return Result.failure(IllegalStateException("Email/password sign-in is debug-only"))
        }
        return runCatching {
            val session = if (createAccount) {
                runCatching { authApi.signUp(email.trim(), password) }
                    .getOrElse { authApi.signInWithPassword(email.trim(), password) }
            } else {
                authApi.signInWithPassword(email.trim(), password)
            }
            sessionStore.save(session)
        }
    }

    override suspend fun signOut(): Result<Unit> = runCatching {
        sessionStore.clear()
    }

    override suspend fun ensureSignedIn(): Result<String> {
        val account = getCurrentAccount()
        if (!account.isGoogleAccount || account.userId == null) {
            return Result.failure(IllegalStateException("Sign-in required"))
        }
        return Result.success(account.userId)
    }

    override fun diagnose(error: Throwable): String {
        return when {
            error is SupabaseTransportException && error.isAuthFailure ->
                "Your Supabase session expired. Sign in again to sync."
            error.message?.isNotBlank() == true -> "Supabase error — ${error.message}"
            else -> "Supabase error — ${error.javaClass.simpleName}"
        }
    }
}
