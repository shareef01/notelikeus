package com.aus.notelikeus.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32

internal object Dpapi {
    private val sessionEntropy: ByteArray = "com.aus.notelikeus/session/v1".encodeToByteArray()

    fun protect(data: ByteArray): ByteArray = protect(data, sessionEntropy)

    fun unprotect(data: ByteArray): ByteArray =
        runCatching { unprotect(data, sessionEntropy) }
            .getOrElse { unprotect(data, null) }

    private fun protect(data: ByteArray, entropy: ByteArray?): ByteArray {
        val outData = DataBlob()
        val result = Crypt32.INSTANCE.CryptProtectData(
            makeDataBlob(data),
            WString("Notelikeus session"),
            entropy?.let { makeDataBlob(it) },
            null,
            null,
            1,
            outData,
        )
        if (result == 0) {
            throw RuntimeException("CryptProtectData failed: ${Kernel32.INSTANCE.GetLastError()}")
        }
        return readAndFreeDataBlob(outData)
    }

    private fun unprotect(data: ByteArray, entropy: ByteArray?): ByteArray {
        val outData = DataBlob()
        val result = Crypt32.INSTANCE.CryptUnprotectData(
            makeDataBlob(data),
            null,
            entropy?.let { makeDataBlob(it) },
            null,
            null,
            1,
            outData,
        )
        if (result == 0) {
            throw RuntimeException("CryptUnprotectData failed: ${Kernel32.INSTANCE.GetLastError()}")
        }
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
}

@Structure.FieldOrder("cbData", "pbData")
internal class DataBlob : Structure() {
    @JvmField var cbData: Int = 0
    @JvmField var pbData: Pointer? = null
}

private interface Crypt32 : Library {
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
