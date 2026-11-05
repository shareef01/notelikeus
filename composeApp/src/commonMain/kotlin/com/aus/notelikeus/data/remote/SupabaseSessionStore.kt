package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SupabaseSessionStore(
    private val persistence: SupabaseSessionPersistence = InMemorySupabaseSessionPersistence(),
) {
    private val lock = Any()
    private val refreshMutex = Mutex()
    @Volatile private var session: SupabaseAuthSession? = persistence.load()
    private var incarnation = 0L

    fun userId(): String? = session?.userId
    fun email(): String? = session?.email
    fun account(): CloudSessionAccount {
        val current = session
        return CloudSessionAccount(current?.userId, current?.email, current != null, current == null)
    }

    fun save(value: SupabaseAuthSession) = synchronized(lock) {
        incarnation++
        session = value
        persistence.save(value)
    }

    suspend fun validAccessToken(refresh: suspend (String) -> SupabaseAuthSession): String? = refreshMutex.withLock {
        val (current, started) = synchronized(lock) { session to incarnation }
        current ?: return@withLock null
        if (System.currentTimeMillis() + EXPIRY_SKEW_MS < current.expiresAtEpochMs) {
            return@withLock current.accessToken
        }
        val token = current.refreshToken ?: return@withLock null
        try {
            val refreshed = refresh(token)
            synchronized(lock) {
                if (incarnation != started || session !== current || refreshed.userId != current.userId) {
                    null
                } else {
                    session = refreshed
                    persistence.save(refreshed)
                    refreshed.accessToken
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.warn(TAG, "Session refresh failed: ${error.javaClass.simpleName}")
            null
        }
    }

    fun clear() = synchronized(lock) {
        incarnation++
        session = null
        persistence.clear()
    }

    private companion object {
        private const val EXPIRY_SKEW_MS = 60_000L
        private const val TAG = "SupabaseSession"
    }
}
