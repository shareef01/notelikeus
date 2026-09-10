package com.aus.notelikeus.data.attachments

/**
 * Seals attachment bytes before they hit disk and opens them on read.
 *
 * Desktop and tests use [NoopAttachmentBytesProtector]. Android supplies an AES-GCM
 * implementation backed by a dedicated Keystore key.
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

/** Identity protector — Desktop / tests / builds without a Keystore. */
object NoopAttachmentBytesProtector : AttachmentBytesProtector {
    override fun looksSealed(payload: ByteArray): Boolean = false

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = plaintext

    override fun open(payload: ByteArray, aad: ByteArray): ByteArray? = payload
}
