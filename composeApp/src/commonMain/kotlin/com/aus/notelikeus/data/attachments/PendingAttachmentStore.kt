package com.aus.notelikeus.data.attachments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/**
 * Process-lifetime cache in front of [AttachmentStagingStore], keyed by owner and attachment id.
 *
 * This used to be the only place staged bytes lived, which meant a note could be persisted holding
 * a `pending:` reference whose bytes died with the process — the attachment survived as metadata
 * pointing at nothing. Durability now belongs to the staging store; this only spares repeated disk
 * reads while previewing and uploading, and every miss falls through to the durable copy.
 */
class PendingAttachmentCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, PendingAttachment>()

    suspend fun put(ownerId: String, attachmentId: String, value: PendingAttachment) {
        mutex.withLock { entries[key(ownerId, attachmentId)] = value }
    }

    suspend fun get(ownerId: String, attachmentId: String): PendingAttachment? =
        mutex.withLock { entries[key(ownerId, attachmentId)] }

    suspend fun remove(ownerId: String, attachmentId: String) {
        mutex.withLock { entries.remove(key(ownerId, attachmentId)) }
    }

    /** Drops every cached entry. Used on sign-out so cached bytes cannot outlive the session. */
    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    private fun key(ownerId: String, attachmentId: String) = "$ownerId/$attachmentId"
}
