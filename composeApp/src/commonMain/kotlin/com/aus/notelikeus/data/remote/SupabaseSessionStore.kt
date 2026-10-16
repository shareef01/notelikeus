package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppLog

class SupabaseSessionStore(
    private val persistence: SupabaseSessionPersistence = InMemorySupabaseSessionPersistence(),
) {
    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var email: String? = null
    @Volatile private var expiresAtEpochMs: Long = 0L

    init {
        persistence.load()?.let { restore(it) }
    }

    fun hasSession(): Boolean = accessToken != null

    fun userId(): String? = userId

    fun email(): String? = email

    fun account(): CloudSessionAccount = CloudSessionAccount(
        userId = userId,
        email = email,
        isSignedIn = hasSession(),
        isAnonymous = !hasSession(),
    )

    fun save(session: SupabaseAuthSession) {
        restore(session)
        persistence.save(session)
    }

    suspend fun validAccessToken(refresh: suspend (String) -> SupabaseAuthSession): String? {
        val token = accessToken ?: return null
        if (System.currentTimeMillis() + EXPIRY_SKEW_MS < expiresAtEpochMs) {
            return token
        }
        val currentRefresh = refreshToken ?: return null
        return runCatching {
            val refreshed = refresh(currentRefresh)
            save(refreshed)
            refreshed.accessToken
        }.onFailure { error ->
            // The only trace a rejected refresh token leaves on a release build.
            AppLog.warn(TAG, "Session refresh failed: ${error.javaClass.simpleName} ${error.message?.take(200)}")
        }.getOrNull()
    }

    fun clear() {
        accessToken = null
        refreshToken = null
        userId = null
        email = null
        expiresAtEpochMs = 0L
        persistence.clear()
    }

    private fun restore(session: SupabaseAuthSession) {
        accessToken = session.accessToken
        refreshToken = session.refreshToken
        userId = session.userId
        email = session.email
        expiresAtEpochMs = session.expiresAtEpochMs
    }

    private companion object {
        private const val EXPIRY_SKEW_MS = 60_000L
        private const val TAG = "SupabaseSession"
    }
}
