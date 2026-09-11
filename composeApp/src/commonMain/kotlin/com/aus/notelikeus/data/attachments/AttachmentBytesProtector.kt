package com.aus.notelikeus.data.attachments

/**
 * Seals attachment bytes before they hit disk and opens them on read.
 *
 * Tests use [NoopAttachmentBytesProtector] or a software-key double. Android supplies AES-GCM
 * backed by a dedicated Keystore key; Desktop supplies AES-GCM under a DPAPI-sealed key file.
 */
interface AttachmentBytesProtector {
    /** Whether [payload] looks like a sealed attachment blob (magic prefix). */
    fun looksSealed(payload: ByteArray): Boolean

    /** Encrypts [plaintext] under [aad]. Returns the on-disk payload. */
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * Decrypts a sealed payload, or returns [payload] unchanged when it is legacy plaintext.
     * Returns null when the payload claims to be sealed but authentication fails.
     */
    fun open(payload: ByteArray, aad: ByteArray): ByteArray?
}

/** Identity protector — tests and builds without a platform seal. */
object NoopAttachmentBytesProtector : AttachmentBytesProtector {
    override fun looksSealed(payload: ByteArray): Boolean = false

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = plaintext

    override fun open(payload: ByteArray, aad: ByteArray): ByteArray? = payload
}
