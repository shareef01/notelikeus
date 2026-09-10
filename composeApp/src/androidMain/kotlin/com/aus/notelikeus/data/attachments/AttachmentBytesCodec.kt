package com.aus.notelikeus.data.attachments

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * On-disk attachment blob format: `NLA1` || IV(12) || ciphertext+tag.
 *
 * The provider generates the IV (AndroidKeyStore keys require randomized encryption). AAD binds
 * the ciphertext to a path identity so a swapped file fails authentication rather than silently
 * decoding under the wrong name.
 */
internal object AttachmentBytesCodec {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val MIN_SEALED = 4 + IV_SIZE + 16 // magic + iv + empty-tag minimum

    fun looksSealed(payload: ByteArray): Boolean =
        payload.size >= MIN_SEALED && payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun seal(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Unexpected GCM IV size: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        val out = ByteArray(MAGIC.size + iv.size + ciphertext.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, iv.size)
        System.arraycopy(ciphertext, 0, out, MAGIC.size + iv.size, ciphertext.size)
        return out
    }

    fun open(key: SecretKey, payload: ByteArray, aad: ByteArray): ByteArray {
        require(looksSealed(payload)) { "Not a sealed attachment blob" }
        val iv = payload.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)
        val ciphertext = payload.copyOfRange(MAGIC.size + IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
