package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig

class SupabaseSessionManager(
    private val authApi: SupabaseAuthApi,
    private val sessionStore: SupabaseSessionStore,
) : CloudSessionManager {

    override fun getCurrentAccount(): CloudSessionAccount = sessionStore.account()

    override suspend fun requestEmailCode(email: String): Result<Unit> = runCatching {
        authApi.requestEmailOtp(email.trim())
    }.recoverCatching { throw EmailCodeException(describeEmailCodeFailure(it), it) }

    override suspend fun signInWithEmailCode(email: String, code: String): Result<Unit> = runCatching {
        val session = authApi.verifyEmailOtp(email.trim(), code.trim())
        sessionStore.save(session)
    }.recoverCatching { throw EmailCodeException(describeEmailCodeFailure(it), it) }

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
        if (!account.isSignedIn || account.userId == null) {
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

/** An email-code failure whose [message] is fit to show the user as is. */
class EmailCodeException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * GoTrue answers these calls with raw JSON errors ("HTTP 403 {"code":"otp_expired",...}") that
 * mean nothing on a sign-in screen. Map the ones a user can act on; the rest get a generic line.
 */
internal fun describeEmailCodeFailure(error: Throwable): String {
    val transport = error as? SupabaseTransportException
        ?: return when (error) {
            // A build with no backend configured says so (see requireConfiguredSupabaseUrl).
            is IllegalStateException -> error.message ?: GENERIC_EMAIL_CODE_FAILURE
            else -> "Couldn't reach the server. Check your connection and try again."
        }
    val body = transport.message.orEmpty()
    return when {
        transport.statusCode == 429 || "rate_limit" in body ->
            "Too many attempts. Wait a minute, then try again."
        "email_address_invalid" in body || "validation_failed" in body ->
            "Enter a valid email address."
        "otp_disabled" in body || "signup_disabled" in body ->
            "Email sign-in isn't available right now."
        "otp_expired" in body || transport.statusCode == 403 ->
            "That code is wrong or has expired. Check it, or send a new one."
        else -> GENERIC_EMAIL_CODE_FAILURE
    }
}

private const val GENERIC_EMAIL_CODE_FAILURE = "Sign-in failed. Try again."
