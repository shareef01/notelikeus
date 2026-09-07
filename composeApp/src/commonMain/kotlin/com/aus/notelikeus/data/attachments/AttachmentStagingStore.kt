package com.aus.notelikeus.data.attachments

import kotlinx.serialization.Serializable

/** Owner namespace used for bytes staged while no account is signed in. */
const val GUEST_STAGING_OWNER = "guest"

/**
 * Metadata describing bytes that are staged locally but have not reached R2 yet.
 *
 * [noteId] is null only for the window between picking an image and the first local save of a
 * brand-new note; [AttachmentStagingStore.bindNote] fills it in once Room has issued the id.
 */
@Serializable
data class StagedAttachment(
    val attachmentId: String,
    val ownerId: String,
    val noteId: Long? = null,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
)

/**
 * Durable, owner-scoped staging for attachment bytes awaiting upload.
 *
 * A locally persisted note must never reference bytes that exist only in process memory: the note
 * row survives process death, so the bytes have to as well, or the attachment is metadata pointing
 * at nothing after a restart. Every implementation must therefore commit [stage] to durable storage
 * before returning, and callers must only add the attachment to the note once it has.
 *
 * Staged bytes are namespaced per owner so signing out of one account and into another cannot
 * surface — or upload — the first account's images under the second account's notes.
 */
interface AttachmentStagingStore {
    /**
     * Writes [bytes] durably and returns its metadata, or null if the write failed. Callers must
     * treat null as "attachment not added" rather than staging a reference with no bytes behind it.
     */
    suspend fun stage(
        attachmentId: String,
        ownerId: String,
        noteId: Long?,
        bytes: ByteArray,
        mimeType: String,
    ): StagedAttachment?

    /** Staged bytes for [attachmentId] within [ownerId], or null if nothing is staged. */
    suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray?

    /** Staged metadata for [attachmentId] within [ownerId], or null if nothing is staged. */
    suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment?

    /** Records the Room id a staged attachment belongs to, once the insert has issued one. */
    suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long)

    /**
     * Deletes staged bytes. Only call once the bytes are recoverable elsewhere — the upload is
     * committed server-side, or the user removed the attachment / discarded the note.
     */
    suspend fun release(attachmentId: String, ownerId: String)

    /** Everything currently staged for [ownerId]. Used by restart reconciliation. */
    suspend fun list(ownerId: String): List<StagedAttachment>
}
