package com.aus.notelikeus.data.attachments

data class PendingAttachment(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        return bytes.contentEquals(other.bytes) && mimeType == other.mimeType
    }

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + mimeType.hashCode()
}

object PendingAttachmentStore {
    private val pending = mutableMapOf<String, PendingAttachment>()

    fun put(attachmentId: String, bytes: ByteArray, mimeType: String) {
        pending[attachmentId] = PendingAttachment(bytes, mimeType)
    }

    fun take(attachmentId: String): PendingAttachment? = pending.remove(attachmentId)

    fun peek(attachmentId: String): PendingAttachment? = pending[attachmentId]

    fun clear() {
        pending.clear()
    }
}
