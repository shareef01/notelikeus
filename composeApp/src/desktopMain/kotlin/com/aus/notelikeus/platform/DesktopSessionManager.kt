package com.aus.notelikeus.platform

import com.aus.notelikeus.data.remote.BackendConfig
import com.aus.notelikeus.data.remote.CloudSessionAccount
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.RemoteBackend
import com.aus.notelikeus.data.remote.SupabaseSessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopSessionManager(
    private val tokenStore: DesktopTokenStore,
    private val supabaseSessionManager: SupabaseSessionManager,
) : CloudSessionManager {

    override fun getCurrentAccount(): CloudSessionAccount {
        if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            return supabaseSessionManager.getCurrentAccount()
        }
        return CloudSessionAccount(
            userId = tokenStore.uid(),
            email = tokenStore.email(),
            isGoogleAccount = tokenStore.hasSession(),
            isAnonymous = !tokenStore.hasSession(),
        )
    }

    override suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            return supabaseSessionManager.signInWithGoogle(idToken)
        }
        if (!tokenStore.hasSession() || tokenStore.uid() == null) {
            return Result.failure(IllegalStateException("Sign-in did not produce a usable session"))
        }
        val tokenUid = extractJwtSubject(idToken)
        if (tokenUid != null && tokenUid != tokenStore.uid()) {
            return Result.failure(IllegalStateException("Token uid ($tokenUid) does not match stored session"))
        }
        return Result.success(Unit)
    }

    override suspend fun signInWithEmailPassword(
        email: String,
        password: String,
        createAccount: Boolean,
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Email/password sign-in is not available on desktop"),
    )

    override suspend fun signOut(): Result<Unit> {
        if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            return supabaseSessionManager.signOut()
        }
        tokenStore.clear()
        return Result.success(Unit)
    }

    override suspend fun ensureSignedIn(): Result<String> {
        val account = getCurrentAccount()
        if (!account.isGoogleAccount || account.userId == null) {
            return Result.failure(IllegalStateException("Not signed in"))
        }
        return Result.success(account.userId)
    }

    override fun diagnose(error: Throwable): String =
        if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            supabaseSessionManager.diagnose(error)
        } else {
            error.message?.takeIf { it.isNotBlank() } ?: "Sync failed. Try again."
        }
}

private fun extractJwtSubject(idToken: String): String? {
    return try {
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
        val json = Json.parseToJsonElement(payload).jsonObject
        json["sub"]?.jsonPrimitive?.content ?: json["user_id"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
