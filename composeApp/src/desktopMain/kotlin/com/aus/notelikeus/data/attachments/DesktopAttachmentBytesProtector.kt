package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.platform.Dpapi
import com.aus.notelikeus.util.AppLog
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a random AES-256 key with DPAPI and uses it for per-file [AttachmentBytesCodec] blobs.
 *
 * Key file lives next to the attachment trees under `~/.notelikeus/` (not AppData session storage)
 * so attachment crypto stays co-located. Entropy is dedicated ([Dpapi.attachmentKeyEntropy]) — never
 * the session blob entropy.
 *
 * Inject [blobStore] in tests so Linux CI can exercise seal/open without Crypt32.
 */
class DesktopAttachmentBytesProtector(
    private val keyDir: File = File(System.getProperty("user.home"), ".notelikeus"),
    private val blobStore: SecureBlobStore = DpapiSecureBlobStore,
) : AttachmentBytesProtector {
    @Volatile
    private var cached: SecretKey? = null

    override fun looksSealed(payload: ByteArray): Boolean = AttachmentBytesCodec.looksSealed(payload)

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
        AttachmentBytesCodec.seal(key(), plaintext, aad)

    override fun open(payload: ByteArray, aad: ByteArray): ByteArray? {
        if (!looksSealed(payload)) return payload
        return try {
            AttachmentBytesCodec.open(key(), payload, aad)
        } catch (error: Exception) {
            AppLog.warn(TAG, "Failed to open sealed attachment bytes", error)
            null
        }
    }

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = loadOrCreateKey()
            cached = loaded
            return loaded
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        keyDir.mkdirs()
        val file = keyFile()
        if (file.exists()) {
            val raw = file.readBytes()
            val plaintext = try {
                blobStore.unprotect(raw)
            } catch (error: Exception) {
                // Do not mint a replacement key — that would orphan every sealed attachment.
                AppLog.warn(TAG, "Attachment key file present but DPAPI unwrap failed", error)
                throw error
            }
            require(plaintext.size == KEY_BYTES) {
                "Attachment key file unwrap produced ${plaintext.size} bytes; expected $KEY_BYTES"
            }
            return SecretKeySpec(plaintext, "AES")
        }

        val plaintext = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val sealed = blobStore.protect(plaintext)
        if (!publishByRename(sealed)) {
            throw IllegalStateException("Could not publish attachment AES key file")
        }
        return SecretKeySpec(plaintext, "AES")
    }

    private fun publishByRename(payload: ByteArray): Boolean {
        val tmp = File(keyDir, "$KEY_FILE_NAME.tmp")
        return try {
            tmp.writeBytes(payload)
            if (tmp.renameTo(keyFile())) {
                true
            } else {
                tmp.delete()
                AppLog.warn(TAG, "Could not publish attachment key by rename")
                false
            }
        } catch (error: Exception) {
            tmp.delete()
            AppLog.warn(TAG, "Failed to write attachment key file", error)
            false
        }
    }

    private fun keyFile(): File = File(keyDir, KEY_FILE_NAME)

    companion object {
        private const val TAG = "AttachmentCrypto"
        const val KEY_FILE_NAME = "attachment-aes.key"
        private const val KEY_BYTES = 32
    }
}

/** Narrow seam so unit tests can fake DPAPI on Linux CI. */
interface SecureBlobStore {
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(sealed: ByteArray): ByteArray
}

internal object DpapiSecureBlobStore : SecureBlobStore {
    override fun protect(plaintext: ByteArray): ByteArray =
        Dpapi.protect(plaintext, Dpapi.attachmentKeyEntropy, "Notelikeus attachment key")

    override fun unprotect(sealed: ByteArray): ByteArray =
        Dpapi.unprotect(sealed, Dpapi.attachmentKeyEntropy)
}
