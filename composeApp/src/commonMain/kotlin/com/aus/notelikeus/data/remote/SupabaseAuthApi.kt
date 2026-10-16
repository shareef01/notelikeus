package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SupabaseAuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String,
    val email: String?,
    val expiresAtEpochMs: Long,
)

internal expect suspend fun supabaseAuthPost(
    supabaseUrl: String,
    anonKey: String,
    path: String,
    body: String,
): String

class SupabaseAuthApi(
    private val supabaseUrl: String,
    private val anonKey: String,
    /** The HTTP POST; a seam so tests can check request bodies without a server. */
    private val post: suspend (path: String, body: String) -> String = { path, body ->
        supabaseAuthPost(supabaseUrl, anonKey, path, body)
    },
) {
    /**
     * Asks Supabase to email a one-time sign-in code to [email].
     *
     * `create_user` is true so the same call signs in an existing account and creates a new one:
     * there is no separate sign-up step. The response carries no session; the session comes from
     * [verifyEmailOtp] once the user types the code in.
     *
     * [captchaToken] is the Turnstile token the hosted project requires (D30); GoTrue reads it from
     * `gotrue_meta_security`. Builds without CAPTCHA pass null and send no such field.
     */
    suspend fun requestEmailOtp(email: String, captchaToken: String? = null) {
        requireConfiguredSupabaseUrl(supabaseUrl)
        val security = captchaToken
            ?.let { ""","gotrue_meta_security":{"captcha_token":${jsonString(it)}}""" }
            .orEmpty()
        post(
            "/auth/v1/otp",
            """{"email":${jsonString(email)},"create_user":true$security}""",
        )
    }

    /** Exchanges the emailed one-time code for a session. */
    suspend fun verifyEmailOtp(email: String, token: String): SupabaseAuthSession {
        requireConfiguredSupabaseUrl(supabaseUrl)
        return parseSession(
            post(
                "/auth/v1/verify",
                """{"type":"email","email":${jsonString(email)},"token":${jsonString(token)}}""",
            ),
        )
    }

    suspend fun signInWithPassword(email: String, password: String): SupabaseAuthSession {
        requireConfiguredSupabaseUrl(supabaseUrl)
        return parseSession(
            post(
                "/auth/v1/token?grant_type=password",
                """{"email":${jsonString(email)},"password":${jsonString(password)}}""",
            ),
        )
    }

    suspend fun signUp(email: String, password: String): SupabaseAuthSession {
        requireConfiguredSupabaseUrl(supabaseUrl)
        return parseSession(
            post(
                "/auth/v1/signup",
                """{"email":${jsonString(email)},"password":${jsonString(password)}}""",
            ),
        )
    }

    suspend fun refreshSession(refreshToken: String): SupabaseAuthSession {
        requireConfiguredSupabaseUrl(supabaseUrl)
        return parseSession(
            post(
                "/auth/v1/token?grant_type=refresh_token",
                """{"refresh_token":${jsonString(refreshToken)}}""",
            ),
        )
    }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            for (char in value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

    private fun parseSession(responseBody: String): SupabaseAuthSession {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = root["access_token"]?.jsonPrimitive?.content
            ?: error("Supabase auth response missing access_token")
        val refreshToken = root["refresh_token"]?.jsonPrimitive?.content
        val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        val user = root["user"]?.jsonObject ?: error("Supabase auth response missing user")
        val userId = user["id"]?.jsonPrimitive?.content ?: error("Supabase user missing id")
        val email = user["email"]?.jsonPrimitive?.content
        return SupabaseAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1000,
        )
    }
}
