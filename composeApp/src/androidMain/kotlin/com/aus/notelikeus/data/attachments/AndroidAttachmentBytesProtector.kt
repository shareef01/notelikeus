package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.local.AndroidPassphraseKeyStore
import com.aus.notelikeus.data.local.PassphraseKeyStore
import com.aus.notelikeus.util.AppLog
import javax.crypto.SecretKey

/**
 * Android Keystore-backed [AttachmentBytesProtector].
 *
 * Uses a dedicated alias ([ATTACHMENT_ALIAS]) — never the SQLCipher passphrase key — so a
 * passphrase rotation cannot orphan attachment files and vice versa.
 */
class AndroidAttachmentBytesProtector : AttachmentBytesProtector {
    private val keyStore: PassphraseKeyStore = AndroidPassphraseKeyStore(ATTACHMENT_ALIAS)

    override fun looksSealed(payload: ByteArray): Boolean = AttachmentBytesCodec.looksSealed(payload)

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        return AttachmentBytesCodec.seal(key(), plaintext, aad)
    }

    override fun open(payload: ByteArray, aad: ByteArray): ByteArray? {
        if (!looksSealed(payload)) return payload
        return try {
            AttachmentBytesCodec.open(key(), payload, aad)
        } catch (error: Exception) {
            AppLog.warn(TAG, "Failed to open sealed attachment bytes", error)
            null
        }
    }

    private fun key(): SecretKey = keyStore.getOrCreateKey()

    companion object {
        private const val TAG = "AttachmentCrypto"
        const val ATTACHMENT_ALIAS = "notelikeus_attachment_aes"
    }
}
