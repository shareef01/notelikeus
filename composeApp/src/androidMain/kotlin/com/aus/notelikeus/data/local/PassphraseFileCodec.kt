package com.aus.notelikeus.data.local

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM file payload codec (testable with a software [SecretKey]). */
internal object PassphraseFileCodec {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'U'.code.toByte(), '1'.code.toByte())
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    fun encrypt(key: SecretKey, utf8Plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Deliberately no caller-supplied IV. AndroidKeyStore keys are generated with
        // setRandomizedEncryptionRequired defaulting to true, and such a key rejects a
        // caller-provided IV on encrypt with InvalidAlgorithmParameterException — which used to
        // throw on every call here, so writeToKeystoreFile always failed and the passphrase fell
        // back to the legacy ESP this class exists to replace. Letting the provider generate the IV
        // keeps randomized encryption required (a stronger policy than turning it off) and behaves
        // the same for the software keys used in tests.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Unexpected GCM IV size: ${iv.size}" }
        val ciphertext = cipher.doFinal(utf8Plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(MAGIC.size + iv.size + ciphertext.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, iv.size)
        System.arraycopy(ciphertext, 0, out, MAGIC.size + iv.size, ciphertext.size)
        return out
    }

    fun decrypt(key: SecretKey, payload: ByteArray): String {
        require(payload.size >= MAGIC.size + IV_SIZE + 16) { "Passphrase file too short" }
        require(payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Bad passphrase file magic" }
        val iv = payload.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)
        val ciphertext = payload.copyOfRange(MAGIC.size + IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}
