package com.aus.notelikeus.data.remote

import com.aus.notelikeus.platform.Dpapi
import com.aus.notelikeus.util.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

class DesktopSupabaseSessionPersistence(
    dataDir: File,
) : SupabaseSessionPersistence {
    private val sessionFile = File(dataDir, ".supabase-session")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        dataDir.mkdirs()
    }

    override fun load(): SupabaseAuthSession? {
        if (!sessionFile.exists()) return null
        val raw = runCatching { sessionFile.readBytes() }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        val decrypted = try {
            String(Dpapi.unprotect(raw))
        } catch (error: Throwable) {
            AppLog.warn(TAG, "Supabase session file undecryptable; treating as signed out", error)
            return null
        }
        return try {
            val parsed = json.parseToJsonElement(decrypted).jsonObject
            val access = parsed.stringOrNull("accessToken") ?: return null
            val userId = parsed.stringOrNull("userId") ?: return null
            SupabaseAuthSession(
                accessToken = access,
                refreshToken = parsed.stringOrNull("refreshToken"),
                userId = userId,
                email = parsed.stringOrNull("email"),
                expiresAtEpochMs = parsed["expiresAtEpochMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        } catch (error: Exception) {
            AppLog.warn(TAG, "Supabase session file unreadable; treating as signed out", error)
            null
        }
    }

    override fun save(session: SupabaseAuthSession) {
        val payload = buildJsonObject {
            put("accessToken", JsonPrimitive(session.accessToken))
            put("refreshToken", session.refreshToken?.let(::JsonPrimitive) ?: JsonNull)
            put("userId", JsonPrimitive(session.userId))
            put("email", session.email?.let(::JsonPrimitive) ?: JsonNull)
            put("expiresAtEpochMs", JsonPrimitive(session.expiresAtEpochMs))
        }.toString().encodeToByteArray()
        runCatching {
            sessionFile.writeBytes(Dpapi.protect(payload))
        }.onFailure { error ->
            AppLog.warn(TAG, "Failed to persist Supabase session", error)
        }
    }

    override fun clear() {
        sessionFile.delete()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TAG = "SupabaseSession"
    }
}
