package com.aus.notelikeus.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

class DesktopTokenStore(private val dataDir: File) {

    private val tokenFile = File(dataDir, ".session")
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedIdToken: String? = null
    private var cachedUid: String? = null
    private var cachedEmail: String? = null

    init {
        dataDir.mkdirs()
        cachedIdToken = loadToken()
        cachedIdToken?.let { decodeJwt(it) }
    }

    fun hasSession(): Boolean = cachedIdToken != null
    fun idToken(): String? = cachedIdToken
    fun uid(): String? = cachedUid
    fun email(): String? = cachedEmail

    fun save(idToken: String) {
        cachedIdToken = idToken
        decodeJwt(idToken)
        tokenFile.writeBytes(dpapiProtect(idToken.encodeToByteArray()))
    }

    fun clear() {
        cachedIdToken = null
        cachedUid = null
        cachedEmail = null
        tokenFile.delete()
    }

    private fun loadToken(): String? {
        if (!tokenFile.exists()) return null
        return try { String(dpapiUnprotect(tokenFile.readBytes())) }
        catch (_: Exception) { tokenFile.delete(); null }
    }

    private fun decodeJwt(idToken: String) {
        try {
            val parts = idToken.split(".")
            if (parts.size < 2) return
            val padded = parts[1].padEnd(((parts[1].length + 3) / 4) * 4, '=')
            val payload = String(Base64.getUrlDecoder().decode(padded))
            val obj = json.decodeFromString<kotlinx.serialization.json.JsonObject>(payload)
            cachedUid = obj["sub"]?.jsonPrimitive?.content ?: obj["user_id"]?.jsonPrimitive?.content
            cachedEmail = obj["email"]?.jsonPrimitive?.content
        } catch (_: Exception) { /* JWT decode best-effort */ }
    }
}

// ---- DPAPI via JNA ----

private fun dpapiProtect(data: ByteArray): ByteArray {
    val inData = makeDataBlob(data)
    val outData = DataBlob()
    val result = Crypt32.INSTANCE.CryptProtectData(
        inData, WString("Notelikeus session"),
        null, null, null, 1, outData
    )
    if (result == 0) throw RuntimeException("CryptProtectData failed: ${Kernel32.INSTANCE.GetLastError()}")
    return readDataBlob(outData)
}

private fun dpapiUnprotect(data: ByteArray): ByteArray {
    val inData = makeDataBlob(data)
    val outData = DataBlob()
    val result = Crypt32.INSTANCE.CryptUnprotectData(
        inData, null, null, null, null, 1, outData
    )
    if (result == 0) throw RuntimeException("CryptUnprotectData failed: ${Kernel32.INSTANCE.GetLastError()}")
    return readDataBlob(outData)
}

private fun makeDataBlob(data: ByteArray): DataBlob {
    val blob = DataBlob()
    blob.cbData = data.size
    val mem = Memory(data.size.toLong())
    mem.write(0, data, 0, data.size)
    blob.pbData = mem
    return blob
}

private fun readDataBlob(blob: DataBlob): ByteArray {
    if (blob.cbData == 0 || blob.pbData == null) return ByteArray(0)
    val out = ByteArray(blob.cbData)
    blob.pbData!!.read(0, out, 0, blob.cbData)
    return out
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
