package com.aus.notelikeus.data.remote

class SupabaseSessionAccessTokenProvider(
    private val sessionStore: SupabaseSessionStore,
    private val authApi: SupabaseAuthApi,
) : SupabaseAccessTokenProvider {
    override suspend fun accessToken(): String? {
        val fromSession = sessionStore.validAccessToken { refreshToken ->
            authApi.refreshSession(refreshToken)
        }
        if (fromSession != null) return fromSession
        return System.getenv("NOTELIKEUS_SUPABASE_ACCESS_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
