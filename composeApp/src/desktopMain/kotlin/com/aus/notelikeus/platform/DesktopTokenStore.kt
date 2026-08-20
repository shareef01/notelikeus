package com.aus.notelikeus.platform

import com.aus.notelikeus.util.AppLog
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Persists the desktop Firebase session and keeps its ID token usable.
 *
 * Firebase ID tokens expire after one hour. Storing only the ID token meant that after that hour
 * every Firestore call came back 401 while [hasSession] still reported a signed-in user — and
 * because the transport swallowed the 401 and returned an empty list, the sync engine concluded
 * the cloud had been emptied and deleted the local notes. The refresh token below is what stops
 * that clock from running out.
 */
class DesktopTokenStore(
    private val dataDir: File,
    private val firebaseApiKey: String
) {

    private val tokenFile = File(dataDir, ".session")
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val refreshMutex = Mutex()

    // Written under refreshMutex but read outside it — validIdToken() checks the token and expiry
    // before taking the lock, and hasSession()/uid()/email() never take it at all. @Volatile is what
    // makes those reads see the last write; without it a stale read can hand out an already-expired
    // token or report a signed-out user as still signed in.
    @Volatile private var cachedIdToken: String? = null
    @Volatile private var cachedRefreshToken: String? = null
    @Volatile private var cachedExpiresAt: Long = 0L
    @Volatile private var cachedUid: String? = null
    @Volatile private var cachedEmail: String? = null

    init {
        dataDir.mkdirs()
        load()
    }

    fun hasSession(): Boolean = cachedIdToken != null
    fun uid(): String? = cachedUid
    fun email(): String? = cachedEmail

    /** The raw cached token, expired or not. Prefer [validIdToken] for anything network-facing. */
    fun idToken(): String? = cachedIdToken

    /**
     * Returns an ID token that is good for at least [EXPIRY_SKEW_MS], refreshing it first if
     * needed. Returns null when there is no session or the refresh was rejected — callers must
     * treat that as "signed out", never as "the cloud is empty".
     */
    suspend fun validIdToken(): String? {
        val current = cachedIdToken ?: return null
        if (System.currentTimeMillis() < cachedExpiresAt - EXPIRY_SKEW_MS) return current
        return refresh()
    }

    /** Persists a freshly exchanged session. Expiry comes from the token's own `exp` claim. */
    fun save(idToken: String, refreshToken: String?) {
        cachedIdToken = idToken
        if (refreshToken != null) cachedRefreshToken = refreshToken
        decodeJwt(idToken)
        persist()
    }

    fun clear() {
        cachedIdToken = null
        cachedRefreshToken = null
        cachedExpiresAt = 0L
        cachedUid = null
        cachedEmail = null
        tokenFile.delete()
    }

    /**
     * Exchanges the refresh token for a new ID token.
     *
     * Serialized behind a mutex so a burst of concurrent syncs performs one refresh instead of
     * racing several. A rejected refresh token means the session is genuinely over, so the
     * session is cleared rather than left in a half-valid state.
     */
    private suspend fun refresh(): String? = refreshMutex.withLock {
        // Another caller may have refreshed while this one waited for the lock.
        val current = cachedIdToken
        if (current != null && System.currentTimeMillis() < cachedExpiresAt - EXPIRY_SKEW_MS) {
            return@withLock current
        }
        val refreshToken = cachedRefreshToken ?: return@withLock null

        val body = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val request = HttpRequest.newBuilder()
            .uri(URI("https://securetoken.googleapis.com/v1/token?key=$firebaseApiKey"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
        } catch (error: Exception) {
            // Offline or a transient network fault — keep the session so a later sync can retry.
            AppLog.warn(TAG, "Token refresh request failed; keeping session for retry", error)
            return@withLock null
        }

        if (response.statusCode() !in 200..299) {
            // Only a 4xx that names the credential means the refresh token is genuinely dead
            // (revoked, password change, account deleted) — that is worth signing the user out
            // for. Everything else is the server having a bad moment: a 429 or a 5xx from
            // securetoken.googleapis.com used to land here too and destroy the refresh token, so a
            // transient outage cost the user the whole browser OAuth loop to get back in. Those
            // keep the session, exactly like the network faults and unparseable bodies around this.
            if (response.statusCode() in REVOCATION_STATUS_CODES) {
                clear()
            }
            return@withLock null
        }

        // A 2xx is not a guarantee of JSON: a captive portal or intercepting proxy answers 200 with
        // HTML. Treat an unreadable body like the network faults above — keep the session and let a
        // later sync retry — rather than throwing out of validIdToken() into the sync engine.
        val payload = try {
            json.decodeFromString<JsonObject>(response.body())
        } catch (error: Exception) {
            AppLog.warn(TAG, "Refresh response was not JSON (status ${response.statusCode()}); keeping session", error)
            return@withLock null
        }
        val newIdToken = payload["id_token"]?.jsonPrimitive?.content ?: return@withLock null
        save(newIdToken, payload["refresh_token"]?.jsonPrimitive?.content)
        newIdToken
    }

    // ---- persistence ----

    private fun persist() {
        val payload = buildJsonObject {
            put("idToken", cachedIdToken)
            put("refreshToken", cachedRefreshToken)
        }
        try {
            tokenFile.writeBytes(dpapiProtect(payload.toString().encodeToByteArray(), SESSION_ENTROPY))
        } catch (error: Throwable) {
            // Session persistence is best-effort; the in-memory session still works this run.
            // Throwable, not Exception: on a non-Windows JVM (desktop CI) loading Crypt32 fails
            // with UnsatisfiedLinkError, which is an Error and would otherwise escape save().
            AppLog.warn(TAG, "Failed to persist session to disk; in-memory session still active", error)
        }
    }

    private fun load() {
        if (!tokenFile.exists()) return
        // load() runs from init, so an unreadable-but-present .session (locked by another process,
        // permissions changed) would otherwise throw out of the constructor and fail the whole Koin
        // graph — the app would not start at all. Every other failure below is already handled by
        // falling back to "no session"; this one belongs with them.
        val raw = try {
            tokenFile.readBytes()
        } catch (error: Exception) {
            AppLog.warn(TAG, "Session file present but unreadable; starting without a session", error)
            return
        }

        // Sessions written before this app-specific entropy was introduced were protected without
        // it, and DPAPI will not open them with it. Read those once through the legacy path and
        // re-persist below, so an upgrade does not silently sign the user out.
        var wasLegacyBlob = false
        val decrypted = try {
            String(dpapiUnprotect(raw, SESSION_ENTROPY))
        } catch (error: Exception) {
            try {
                String(dpapiUnprotect(raw, null)).also { wasLegacyBlob = true }
            } catch (legacyError: Exception) {
                AppLog.warn(TAG, "Session file undecryptable by DPAPI (new and legacy entropy); deleting it", legacyError)
                tokenFile.delete()
                return
            }
        }

        val parsed = try {
            json.parseToJsonElement(decrypted).jsonObject
        } catch (_: Exception) {
            // Sessions written before refresh support held a bare JWT. Honour it until it
            // expires; with no refresh token the user simply signs in again after that.
            cachedIdToken = decrypted
            decodeJwt(decrypted)
            if (wasLegacyBlob) persist()
            return
        }

        cachedIdToken = parsed.stringOrNull("idToken")
        cachedRefreshToken = parsed.stringOrNull("refreshToken")
        cachedIdToken?.let { decodeJwt(it) }
        if (wasLegacyBlob) persist()
    }

    /** `jsonPrimitive.content` on a JSON null yields the string "null", so filter it out first. */
    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun decodeJwt(idToken: String) {
        try {
            val parts = idToken.split(".")
            if (parts.size < 2) return
            val padded = parts[1].padEnd(((parts[1].length + 3) / 4) * 4, '=')
            val payload = String(Base64.getUrlDecoder().decode(padded))
            val obj = json.decodeFromString<JsonObject>(payload)
            cachedUid = obj["sub"]?.jsonPrimitive?.content ?: obj["user_id"]?.jsonPrimitive?.content
            cachedEmail = obj["email"]?.jsonPrimitive?.content
            // `exp` is seconds since epoch. Deriving expiry from the token beats trusting a
            // separately reported expires_in that may not match what was actually issued.
            cachedExpiresAt = obj["exp"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L
        } catch (error: Exception) {
            AppLog.warn(TAG, "JWT decode failed; session kept without uid/email/expiry", error)
        }
    }

    private companion object {
        private const val TAG = "DesktopTokenStore"
        /** Refresh this far ahead of the real expiry so an in-flight request cannot age out. */
        const val EXPIRY_SKEW_MS = 5 * 60 * 1000L

        /**
         * Statuses that mean the refresh token will never work again, so the session should be
         * dropped. 400 is what Google's token endpoint actually returns for
         * `invalid_grant`; 401 and 403 are included for completeness.
         */
        val REVOCATION_STATUS_CODES = setOf(400, 401, 403)
    }
}

// ---- DPAPI via JNA ----

/**
 * Secondary entropy mixed into the session blob.
 *
 * DPAPI at user scope alone means any process running as the same Windows user can decrypt
 * `.session` simply by calling CryptUnprotectData on it. Requiring this constant as well means a
 * caller has to know it, which is a low bar but a higher one than none.
 */
private val SESSION_ENTROPY: ByteArray = "com.aus.notelikeus/session/v1".encodeToByteArray()

private fun dpapiProtect(data: ByteArray, entropy: ByteArray?): ByteArray {
    val outData = DataBlob()
    val result = Crypt32.INSTANCE.CryptProtectData(
        makeDataBlob(data), WString("Notelikeus session"),
        entropy?.let { makeDataBlob(it) }, null, null, 1, outData
    )
    if (result == 0) throw RuntimeException("CryptProtectData failed: ${Kernel32.INSTANCE.GetLastError()}")
    return readAndFreeDataBlob(outData)
}

private fun dpapiUnprotect(data: ByteArray, entropy: ByteArray?): ByteArray {
    val outData = DataBlob()
    val result = Crypt32.INSTANCE.CryptUnprotectData(
        makeDataBlob(data), null,
        entropy?.let { makeDataBlob(it) }, null, null, 1, outData
    )
    if (result == 0) throw RuntimeException("CryptUnprotectData failed: ${Kernel32.INSTANCE.GetLastError()}")
    return readAndFreeDataBlob(outData)
}

private fun makeDataBlob(data: ByteArray): DataBlob {
    val blob = DataBlob()
    blob.cbData = data.size
    val mem = Memory(data.size.toLong())
    mem.write(0, data, 0, data.size)
    blob.pbData = mem
    return blob
}

/**
 * Copies the blob out and releases it.
 *
 * DPAPI allocates the output buffer with LocalAlloc and hands ownership to the caller, so skipping
 * LocalFree leaks a native allocation on every protect and unprotect.
 */
private fun readAndFreeDataBlob(blob: DataBlob): ByteArray {
    val pointer = blob.pbData ?: return ByteArray(0)
    return try {
        if (blob.cbData == 0) ByteArray(0) else ByteArray(blob.cbData).also {
            pointer.read(0, it, 0, blob.cbData)
        }
    } finally {
        Kernel32.INSTANCE.LocalFree(pointer)
    }
}

@Structure.FieldOrder("cbData", "pbData")
class DataBlob : Structure() {
    @JvmField var cbData: Int = 0
    @JvmField var pbData: Pointer? = null
}

interface Crypt32 : Library {
    companion object {
        val INSTANCE: Crypt32 = Native.load("Crypt32", Crypt32::class.java)
    }
    fun CryptProtectData(
        pDataIn: DataBlob, szDataDescr: WString?, pOptionalEntropy: DataBlob?,
        pvReserved: Pointer?, pPromptStruct: Pointer?, dwFlags: Int, pDataOut: DataBlob
    ): Int
    fun CryptUnprotectData(
        pDataIn: DataBlob, szDataDescr: Pointer?, pOptionalEntropy: DataBlob?,
        pvReserved: Pointer?, pPromptStruct: Pointer?, dwFlags: Int, pDataOut: DataBlob
    ): Int
}
