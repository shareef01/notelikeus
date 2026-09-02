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
) {
    suspend fun signInWithGoogleIdToken(idToken: String): SupabaseAuthSession =
        parseSession(
            supabaseAuthPost(
                supabaseUrl,
                anonKey,
                "/auth/v1/token?grant_type=id_token",
                """{"provider":"google","id_token":${jsonString(idToken)}}""",
            ),
        )

    suspend fun signInWithPassword(email: String, password: String): SupabaseAuthSession =
        parseSession(
            supabaseAuthPost(
                supabaseUrl,
                anonKey,
                "/auth/v1/token?grant_type=password",
                """{"email":${jsonString(email)},"password":${jsonString(password)}}""",
            ),
        )

    suspend fun signUp(email: String, password: String): SupabaseAuthSession =
        parseSession(
            supabaseAuthPost(
                supabaseUrl,
                anonKey,
                "/auth/v1/signup",
                """{"email":${jsonString(email)},"password":${jsonString(password)}}""",
            ),
        )

    suspend fun refreshSession(refreshToken: String): SupabaseAuthSession =
        parseSession(
            supabaseAuthPost(
                supabaseUrl,
                anonKey,
                "/auth/v1/token?grant_type=refresh_token",
                """{"refresh_token":${jsonString(refreshToken)}}""",
            ),
        )

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
