package com.aus.notelikeus.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aus.notelikeus.data.local.AndroidPassphraseKeyStore
import com.aus.notelikeus.data.local.PassphraseFileCodec
import com.aus.notelikeus.data.local.PassphraseKeyStore
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

/**
 * Persists the GoTrue session as an AES-GCM file under [Context.getFilesDir], keyed in
 * AndroidKeyStore — the same shape as [com.aus.notelikeus.data.local.DatabaseKeyManager], and the
 * replacement for deprecated EncryptedSharedPreferences.
 *
 * Legacy ESP (and the plaintext fallback ESP used to land on) is read once, copied into the file,
 * then cleared. An undecryptable file is treated as signed-out and left on disk, matching desktop
 * DPAPI: fail-closed, not a wipe.
 */
class AndroidSupabaseSessionPersistence internal constructor(
    private val context: Context,
    private val keyStore: PassphraseKeyStore,
) : SupabaseSessionPersistence {

    constructor(context: Context) : this(
        context,
        AndroidPassphraseKeyStore(AndroidPassphraseKeyStore.SESSION_ALIAS),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    override fun load(): SupabaseAuthSession? = synchronized(lock) {
        when (val fromFile = readFromKeystoreFile()) {
            is SessionReadResult.Decrypted -> return fromFile.session
            SessionReadResult.Absent -> Unit
            SessionReadResult.Corrupt -> return null
        }
        val legacy = readFromLegacyPrefs() ?: return null
        if (writeToKeystoreFile(legacy)) {
            clearLegacyPrefs()
        }
        legacy
    }

    override fun save(session: SupabaseAuthSession) {
        synchronized(lock) {
            if (writeToKeystoreFile(session)) {
                clearLegacyPrefs()
            } else {
                Log.w(TAG, "Could not persist Supabase session to Keystore file")
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            sessionFile().delete()
            File(context.filesDir, "$SESSION_FILE.tmp").delete()
            clearLegacyPrefs()
        }
    }

    private fun sessionFile(): File = File(context.filesDir, SESSION_FILE)

    private sealed interface SessionReadResult {
        data class Decrypted(val session: SupabaseAuthSession) : SessionReadResult
        object Absent : SessionReadResult
        object Corrupt : SessionReadResult
    }

    private fun readFromKeystoreFile(): SessionReadResult {
        val file = sessionFile()
        if (!file.exists()) return SessionReadResult.Absent
        val decrypted = try {
            PassphraseFileCodec.decrypt(keyStore.getOrCreateKey(), file.readBytes())
        } catch (error: Exception) {
            Log.w(TAG, "Supabase session file undecryptable; treating as signed out", error)
            return SessionReadResult.Corrupt
        }
        return parseSession(decrypted)?.let { SessionReadResult.Decrypted(it) }
            ?: SessionReadResult.Corrupt
    }

    private fun writeToKeystoreFile(session: SupabaseAuthSession): Boolean {
        val payload = try {
            PassphraseFileCodec.encrypt(keyStore.getOrCreateKey(), encodeSession(session))
        } catch (error: Exception) {
            Log.w(TAG, "Keystore key could not encrypt the Supabase session", error)
            return false
        }
        val tmp = File(context.filesDir, "$SESSION_FILE.tmp")
        val dest = sessionFile()
        return try {
            tmp.writeBytes(payload)
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                Log.w(TAG, "Could not replace existing session file")
                return false
            }
            if (tmp.renameTo(dest)) {
                true
            } else {
                tmp.delete()
                Log.w(TAG, "Could not publish session file by rename")
                false
            }
        } catch (error: Exception) {
            tmp.delete()
            Log.w(TAG, "Failed to write Keystore session file", error)
            false
        }
    }

    private fun encodeSession(session: SupabaseAuthSession): String = buildJsonObject {
        put("accessToken", JsonPrimitive(session.accessToken))
        put("refreshToken", session.refreshToken?.let(::JsonPrimitive) ?: JsonNull)
        put("userId", JsonPrimitive(session.userId))
        put("email", session.email?.let(::JsonPrimitive) ?: JsonNull)
        put("expiresAtEpochMs", JsonPrimitive(session.expiresAtEpochMs))
    }.toString()

    private fun parseSession(raw: String): SupabaseAuthSession? = try {
        val parsed = json.parseToJsonElement(raw).jsonObject
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
        Log.w(TAG, "Supabase session file unreadable; treating as signed out", error)
        null
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun readFromLegacyPrefs(): SupabaseAuthSession? =
        sessionFrom(openLegacyEsp()) ?: sessionFrom(openFallbackPrefs())

    private fun sessionFrom(prefs: SharedPreferences?): SupabaseAuthSession? {
        if (prefs == null) return null
        val access = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        val userId = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return SupabaseAuthSession(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            userId = userId,
            email = prefs.getString(KEY_EMAIL, null),
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES, 0L),
        )
    }

    @Suppress("DEPRECATION")
    private fun openLegacyEsp(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Exception) {
        Log.w(TAG, "Legacy ESP unavailable", error)
        null
    }

    private fun openFallbackPrefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)

    private fun clearLegacyPrefs() {
        try {
            openLegacyEsp()?.edit()?.clear()?.apply()
            context.deleteSharedPreferences(PREFS_NAME)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to clear legacy ESP after session migration", error)
        }
        try {
            openFallbackPrefs().edit().clear().apply()
            context.deleteSharedPreferences(PREFS_NAME_FALLBACK)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to clear plaintext session fallback", error)
        }
    }

    internal companion object {
        const val TAG = "SupabaseSession"
        const val SESSION_FILE = "supabase_session.enc"
        const val PREFS_NAME = "notelikeus_supabase_session"
        const val PREFS_NAME_FALLBACK = "notelikeus_supabase_session_fallback"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_EXPIRES = "expires_at"
    }
}
